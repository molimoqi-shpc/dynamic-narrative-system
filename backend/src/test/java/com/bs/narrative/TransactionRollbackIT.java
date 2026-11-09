package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class TransactionRollbackIT {
    @Autowired GameActionService actions;
    @Autowired SessionStateService state;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockBean LLMProvider model;
    @SpyBean StorySummaryService summaries;
    @Test void failedAnchorWriteRollsBackRoundStatsAndAllRelatedRecords() {
        when(model.chatForJson(anyList())).thenThrow(new LLMApiException("test timeout",true));
        doAnswer(call->{assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id='rollback_test' AND source_type='TURN_EVENT'",Integer.class)>0,"must reach an actual uncommitted frame before the simulated failure");throw new DataIntegrityViolationException("simulated write failure");}).when(summaries)
                .persistSummaryAtRound(eq("rollback_test"),anyString(),anyInt(),any());
        int checkpoints=jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint",Integer.class);
        int logs=jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log",Integer.class);
        var tx=new TransactionTemplate(transactions);
        assertThrows(DataIntegrityViolationException.class,()->tx.execute(status -> {
            String sid="rollback_test",pid="rollback_user",role="rollback_role",scene="rollback_scene";
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui) VALUES(?,?,'测试角色','MORTAL','平静',75)",role,sid);
            jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'水岸','[]','测试场景',5,6)",scene,sid);
            jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'rollback_test','unused',?,?)",pid,role,scene,sid);
            jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
            state.initialize(sid,pid);
            actions.processActionWithDb(new ActionRequest(pid,scene,"观察",
                    new ActionRequest.StructuredIntent("OBSERVE",role,null,null,null,1.0,"观察")),sid);
            return null;
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_session WHERE session_id='rollback_test'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE god_id='rollback_role'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_clock WHERE session_id='rollback_test'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_plan WHERE session_id='rollback_test'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id='rollback_test'",Integer.class));
        assertEquals(checkpoints,jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint",Integer.class));
        assertEquals(logs,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log",Integer.class));
    }
}
