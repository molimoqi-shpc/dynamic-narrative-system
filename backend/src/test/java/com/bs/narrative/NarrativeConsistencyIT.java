package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
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

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class NarrativeConsistencyIT {
    @Autowired GameActionService actions;
    @Autowired SessionStateService state;
    @Autowired JdbcTemplate jdbc;
    @MockBean LLMProvider model;
    String sid="consistency_test",pid="consistency_user",role="consistency_role",scene="consistency_scene";
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui) VALUES(?,?,'测试凡人','MORTAL','平静',75)",role,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'真实水岸','[]','测试地点',5,6)",scene,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'consistency_test','unused',?,?)",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
        state.initialize(sid,pid);
    }
    private com.bs.narrative.dto.ActionResponse act() {
        return actions.processActionWithDb(new ActionRequest(pid,scene,"观察水岸",
                new ActionRequest.StructuredIntent("UNKNOWN",role,null,null,null,1.0,"观察")),sid);
    }
    @Test void timeoutKeepsRuleResultVisibleAndSummaryUsesTheUnifiedRound() {
        when(model.chatForJson(anyList())).thenThrow(new LLMApiException("test timeout",true));
        state.begin(sid,pid);state.complete(sid,"先前移动");
        var result=act();
        assertTrue(result.lines().stream().anyMatch(l -> l.godId().equals("narrator") && l.speech().contains(result.outcomeLabel())));
        assertEquals(2,jdbc.queryForObject("SELECT round_no FROM story_summary WHERE session_id=?",Integer.class,sid));
        assertEquals(2,jdbc.queryForObject("SELECT round_no FROM interaction_log WHERE session_id=?",Integer.class,sid));
        assertEquals(3,state.list(sid).size());
        assertEquals(75,jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,role));
    }
    @Test void hallucinatedHiddenStateCannotBecomeAStoryAnchor() {
        String json="{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"水岸平静\"}],\"hidden_summary\":{\"locationName\":\"虚构城\",\"coordX\":999,\"coordY\":999,\"plotSummary\":\"所有目标已完成\",\"presentGods\":[\"fake_god\"],\"keyEvents\":[\"玩家死亡\"],\"stateChanges\":\"归降为零\"}}";
        when(model.chatForJson(anyList())).thenReturn(new LLMResponse(json,json,0,0,0,0));
        act();
        var row=jdbc.queryForMap("SELECT location_name,coord_x,plot_summary,present_gods,state_changes FROM story_summary WHERE session_id=?",sid);
        assertEquals("真实水岸",row.get("location_name"));assertEquals(5,((Number)row.get("coord_x")).intValue());
        assertFalse(row.get("plot_summary").toString().contains("所有目标已完成"));
        assertFalse(row.get("state_changes").toString().contains("归降为零"));
        assertFalse(row.get("present_gods").toString().contains("fake_god"));
        assertTrue(row.get("present_gods").toString().contains(role));
    }
}
