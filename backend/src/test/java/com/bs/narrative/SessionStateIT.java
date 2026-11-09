package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.repository.GodProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** All fixture writes roll back; remote model providers are never called. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class SessionStateIT {
    @Autowired SessionStateService state;
    @Autowired SessionNpcService npcs;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired GodProfileRepository gods;
    private final String sid="test_state",pid="test_account",role="test_role",scene="test_scene";

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,'测试角色','MORTAL','坚定',8,9,10,11,20,75,'[\"骨刃\"]')",role,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'水岸','[]','初始场景',5,6)",scene,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'test_state_user','not-a-password',?,?,'[\"骨刃\"]')",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
        jdbc.update("INSERT INTO player_objective(objective_id,player_id,session_id,title,status,progress) VALUES('goal',?,?,'测试目标','ACTIVE',0)",pid,sid);
        state.initialize(sid,pid);
    }

    @Test void rewindRestoresStatsInventoryObjectivesMapMemoriesAndFutureLogs() {
        assertEquals(1,state.begin(sid,pid));
        jdbc.update("UPDATE god_profile SET base_dou=99,inventory='[]' WHERE god_id=?",role);
        jdbc.update("UPDATE player_objective SET progress=100,status='COMPLETED' WHERE session_id=?",sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('test_future',?,'新地点','[]','未来场景')",sid);
        jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,'test_future','NORTH','北行')",sid,scene);
        jdbc.update("UPDATE player_profile SET current_scene_id='test_future' WHERE player_id=?",pid);
        jdbc.update("INSERT INTO player_memory(player_id,session_id,scope,content) VALUES(?,?,'GLOBAL','未来记忆')",pid,sid);
        jdbc.update("INSERT INTO world_book(session_id,round_no,location_name,event_type,event_description) VALUES(?,1,'新地点','MOVE','未来事件')",sid);
        state.complete(sid,"第一回合");
        assertEquals(2,state.list(sid).size());
        var result=state.restore(sid,0);
        assertEquals(scene,result.get("restoredSceneId"));
        assertEquals(10,jdbc.queryForObject("SELECT base_dou FROM god_profile WHERE god_id=?",Integer.class,role));
        assertTrue(jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,role).contains("骨刃"));
        assertEquals(0,jdbc.queryForObject("SELECT progress FROM player_objective WHERE session_id=?",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE scene_id='test_future'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=?",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid));
        assertEquals(1,state.list(sid).size());
        assertEquals(1,state.begin(sid,pid));
    }

    @Test void rewindRestoresRelationshipsAndSceneMemory() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion) VALUES('test_npc',?,'测试神','DEITY','平静')",sid);
        jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,is_allied,is_backstabbed) VALUES(?,?,'test_npc',25,0,0)",sid,role);
        state.complete(sid,"建立关系");
        state.begin(sid,pid);
        jdbc.update("UPDATE god_relationship SET trust_value=90 WHERE session_id=?",sid);
        jdbc.update("INSERT INTO player_memory(player_id,session_id,scope,ref_id,content) VALUES(?,?,'SCENE',?,'未来场景摘要')",pid,sid,scene);
        state.complete(sid,"改变关系");
        state.restore(sid,1);
        assertEquals(25,jdbc.queryForObject("SELECT trust_value FROM god_relationship WHERE session_id=?",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=?",Integer.class,sid));
    }

    @Test void unavailableHistoryIsRejectedWithoutDeletingState() {
        assertThrows(BusinessException.class,()->state.restore(sid,99));
        assertEquals(1,state.list(sid).size());
        assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
    }

    @Test void resumeRestoresRoleAndRejectsAnotherAccountsSession() {
        jdbc.update("UPDATE player_profile SET god_id='player_01',current_scene_id='scene_fengdu',active_session_id='sess_demo_001' WHERE player_id=?",pid);
        em.clear();
        assertThrows(BusinessException.class,()->state.begin(sid,pid));
        var restored=state.resume(sid,pid);
        assertEquals(role,restored.get("playerGodId"));
        assertEquals(1,state.begin(sid,pid));
        assertThrows(BusinessException.class,()->state.resume(sid,"p_demo"));
    }

    @Test void npcCopiesAreStablePerSessionAndDoNotMutateTemplates() {
        int original=gods.findById("rain_god").orElseThrow().getBaseDou();
        var ids=npcs.resolve(sid,List.of("rain_god","museum_god"));
        assertEquals(2,ids.size());assertFalse(ids.contains("rain_god"));
        assertEquals(ids,npcs.resolve(sid,List.of("rain_god","museum_god")));
        var cloned=gods.findById(ids.get(0)).orElseThrow();
        cloned.setBaseDou(999);gods.saveAndFlush(cloned);em.clear();
        assertEquals(original,gods.findById("rain_god").orElseThrow().getBaseDou());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM god_relationship WHERE session_id=?",Integer.class,sid)>0);
    }
}
