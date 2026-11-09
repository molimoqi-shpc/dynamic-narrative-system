package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL and services; model responses controlled, every fixture transaction rolls back. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class GameplayBranchesIT {
    @Autowired GameActionService actions;
    @Autowired SceneGenerationService exploration;
    @Autowired GameSessionService lifecycle;
    @Autowired PlayerObjectiveService objectives;
    @Autowired SessionStateService state;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockBean LLMProvider models;
    final String sid="branches_test",pid="branches_user",role="branches_role",npc="branches_npc",scene="branches_scene";
    LLMResponse response(String json) {return new LLMResponse(json,json,0,0,0,0);}
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_dou,base_gui,inventory) VALUES(?,?,'测试凡人','MORTAL','坚定',80,75,'[\"骨刃\"]')",role,sid);
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_dou,base_gui) VALUES(?,?,'守桥人','DEITY','平静',5,75)",npc,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'桥头',?,'守桥人站在桥头',0,0)",scene,sid,"[\""+npc+"\"]");
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'branches_test','unused',?,?,'[\"骨刃\"]')",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
        state.initialize(sid,pid);
        when(models.chatForJson(anyList())).thenReturn(response("{\"lines\":[{\"godId\":\""+npc+"\",\"speech\":\"守桥人应声。\",\"innerThought\":\"静观。\"}]}"));
        when(models.chatSubForJson(anyList())).thenReturn(response("{\""+npc+"\":{\"behavior\":\"IGNORE\",\"speech\":\"静观其变。\"}}"));
    }
    ActionRequest request(String intent,String text) {
        return new ActionRequest(pid,scene,text,new ActionRequest.StructuredIntent(intent,role,npc,
                intent.equals("ATTACK")?"dou":null,null,1.0,text));
    }
    @Test void dialogueThenCombatPersistAndRewindExactly() {
        var talk=actions.processActionWithDb(request("TALK","向守桥人请教"),sid);
        assertEquals("NO_OP",talk.outcomeType());
        assertEquals(80,jdbc.queryForObject("SELECT base_dou FROM god_profile WHERE god_id=?",Integer.class,role));
        int originalNpcDou=jdbc.queryForObject("SELECT base_dou FROM god_profile WHERE god_id=?",Integer.class,npc);
        var attack=actions.processActionWithDb(request("ATTACK","攻击守桥人"),sid);
        assertEquals("ATTACKER_WIN",attack.outcomeType());
        assertTrue(jdbc.queryForObject("SELECT base_dou FROM god_profile WHERE god_id=?",Integer.class,npc)<originalNpcDou);
        assertEquals(2,jdbc.queryForObject("SELECT current_round FROM game_session WHERE session_id=?",Integer.class,sid));
        state.restore(sid,1);
        assertEquals(originalNpcDou,jdbc.queryForObject("SELECT base_dou FROM god_profile WHERE god_id=?",Integer.class,npc));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
        assertEquals(2,state.list(sid).size());
    }
    @Test void explorationCreatesReturnEdgeAndRewindRemovesFutureMap() {
        when(models.chatForJson(anyList())).thenReturn(response("{\"direction\":\"EAST\",\"exit_name\":\"石桥\",\"location_name\":\"桥彼岸\",\"scene_description\":\"桥彼岸空无一人。\",\"present_npcs\":[]}"),response("{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"你来到桥彼岸。\"}]}"));
        var moved=exploration.explore(sid,pid,"向东走过石桥");
        assertNotEquals(scene,moved.get("sceneId"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));
        assertEquals(moved.get("sceneId"),jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        state.restore(sid,0);
        assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));
    }
    @Test void deathEndingRewindAndRestartKeepSessionOwnership() {
        jdbc.update("UPDATE game_session SET status='DEAD',death_summary='已登记的实际死亡终局' WHERE session_id=?",sid);em.clear();
        assertTrue(lifecycle.checkDeath(sid,pid));
        when(models.chatForJson(anyList())).thenReturn(response("{\"death_summary\":\"测试命途终结\",\"final_evaluation\":\"其志可嘉\"}"));
        var death=lifecycle.processDeath(sid,pid);
        assertEquals("DEFEAT",death.get("ending"));
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));
        state.restore(sid,0);
        assertFalse(lifecycle.checkDeath(sid,pid));
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));
        var restarted=lifecycle.restart(pid,"DREAMER",true,sid);
        String next=(String)restarted.get("newSessionId");
        em.flush();
        assertEquals(pid,jdbc.queryForObject("SELECT player_id FROM game_session WHERE session_id=?",String.class,next));
        assertEquals("CREATING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,next));
        assertEquals(sid,jdbc.queryForObject("SELECT prev_session_id FROM game_session WHERE session_id=?",String.class,next));
    }
    @Test void completedObjectivesAndLegacyTerminalFlagsNeverEndALivingWorld() {
        assertEquals("ONGOING",objectives.evaluateEnding(sid,pid,false).get("ending"));
        jdbc.update("INSERT INTO player_objective(objective_id,player_id,session_id,title,status,progress) VALUES('branches_goal',?,?,'守护桥头','COMPLETED',100)",pid,sid);
        assertEquals("ONGOING",objectives.evaluateEnding(sid,pid,false).get("ending"),"personal goals do not force an ending");
        jdbc.update("INSERT INTO game_goal_rule(session_id,owner_id,goal_id,rule_json,terminal_flag) VALUES(?,?, 'explicit_terminal',?,true)",sid,pid,"{\"type\":\"AT_SCENE\",\"sceneId\":\""+scene+"\"}");
        assertEquals("ONGOING",objectives.evaluateEnding(sid,pid,false).get("ending"));
        objectives.markSessionVictory(sid);em.flush();
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));
        state.restore(sid,0);
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));
        assertEquals("ONGOING",objectives.evaluateEnding(sid,pid,false).get("ending"));
    }

    @Test void observingAlsoRegistersThePlayersInventoryBeforeTheRoundCheckpoint() {
        actions.processActionWithDb(request("OBSERVE","查看桥头"),sid);
        var row=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE'",String.class,sid,role);
        assertTrue(row.contains("骨刃"));assertTrue(row.contains("HELD"));
        state.restore(sid,1);
        assertEquals(row,jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE'",String.class,sid,role));
    }

    @Test void zeroOrNegativeGuiIsNotDeathAndDoesNotCreateATerminalEvent(){
        for(int value:List.of(0,-5)){jdbc.update("UPDATE god_profile SET base_gui=? WHERE god_id=?",value,role);em.clear();assertFalse(lifecycle.checkDeath(sid,pid));assertThrows(com.bs.narrative.exception.BusinessException.class,()->lifecycle.processDeath(sid,pid));}
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));verifyNoInteractions(models);
    }
    @Test void deathQueryUsesTheOwnedSessionAndTerminalReadsAreIdempotent(){
        assertFalse(lifecycle.checkDeath(sid,"other-owner"));assertFalse(lifecycle.checkDeath("missing",pid));
        jdbc.update("UPDATE game_session SET status='DEAD',death_summary='已登记的结局' WHERE session_id=?",sid);em.clear();
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Long.class,sid);
        assertTrue(lifecycle.checkDeath(sid,pid));var first=lifecycle.processDeath(sid,pid);var second=lifecycle.processDeath(sid,pid);assertEquals(first,second);assertEquals("已登记的结局",first.get("deathSummary"));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Long.class,sid));verifyNoInteractions(models);
    }

}
