package com.bs.narrative;
import com.bs.narrative.service.CharacterPanelService;
import com.bs.narrative.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class CharacterPanelIT {
    @Autowired CharacterPanelService panel;
    @Autowired JdbcTemplate jdbc;
    final String sid="panel_test",pid="panel_user";
    @BeforeEach void seed() {
        for(String role:List.of("panel_role","panel_friend","panel_enemy","panel_known","panel_foreign"))
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',100,75,'[\"骨刃\",\"水囊\"]')",role,role.equals("panel_foreign")?"private_other":sid,role);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('panel_scene',?,'水岸','[]','平静')",sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,'panel_role','panel_user','unused','panel_scene',?)",pid,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,'panel_role','panel_scene','PLAYING')",sid,pid);
        for(String target:List.of("panel_friend","panel_enemy","panel_known","panel_foreign"))
            jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,is_allied,note) VALUES(?,'panel_role',?,?,?,?)",sid,target,target.equals("panel_enemy")?15:target.equals("panel_known")?50:90,target.equals("panel_friend"),"我方记录");
        jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,note) VALUES(?,'panel_friend','panel_role',1,'NPC隐藏秘密')",sid);
    }
    @Test void inventoryAndDivinityReflectRealStateNotMasteryPromotion() {
        var result=panel.describe(sid,pid);
        assertEquals(List.of("骨刃","水囊"),result.get("inventory"));
        assertEquals("普通人 · 身份以故事记录为准",result.get("divinity"));
        assertEquals(100,((Map<?,?>)result.get("baseStats")).get("ben"));
        assertEquals("six-stats-v2",((Map<?,?>)result.get("formula")).get("version"));
    }
    @Test void onlyOwnDirectedRelationshipsAndSessionCharactersAreVisible() throws Exception {
        var result=panel.describe(sid,pid);
        assertEquals(1,((List<?>)result.get("friends")).size());
        assertEquals(1,((List<?>)result.get("enemies")).size());
        assertEquals(1,((List<?>)result.get("acquaintances")).size());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("NPC隐藏秘密"));assertFalse(json.contains("panel_foreign"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_session WHERE session_id=?",Integer.class,sid));
    }
    @Test void anotherAccountCannotReadPanel() {
        assertThrows(BusinessException.class,()->panel.describe(sid,"someone_else"));
    }
    @Test void legacyOwnedSessionCanResolveItsOwnRoleWithoutMutatingSave() {
        jdbc.update("UPDATE game_session SET player_god_id=NULL WHERE session_id=?",sid);
        var result=panel.describe(sid,pid);
        assertEquals("panel_role",result.get("godId"));
        assertNull(jdbc.queryForObject("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid));
    }
}
