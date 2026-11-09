package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.domain.*;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class InquiryNotebookIT {
    @Autowired InquiryService inquiry;@Autowired KnowledgeService knowledge;@Autowired JdbcTemplate jdbc;@Autowired SessionStateService state;
    @SpyBean WorldBookService world;@MockBean LLMProvider model;
    String sid="inquiry_book_it",pid="inquiry_book_user",god="inquiry_book_role",scene="inquiry_book_scene";WorldBook event;
    @BeforeEach void seed(){
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,'测试行者','MORTAL','平静',75,'[]')",god,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'水岸','[]','水岸可供打听。',0,0)",scene,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'inquiry_book_test','unused',?,?)",pid,god,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
        jdbc.update("INSERT INTO world_book(session_id,round_no,coord_x,coord_y,location_name,event_type,event_description) VALUES(?,0,0,0,'水岸','STORY_MILESTONE','旧桥恢复通行。')",sid);
        event=new WorldBook();event.setSessionId(sid);event.setEntryId(jdbc.queryForObject("SELECT MAX(entry_id) FROM world_book WHERE session_id=?",Long.class,sid));event.setRoundNo(0);event.setLocationName("水岸");event.setEventDescription("旧桥恢复通行。");event.setCoordX(0);event.setCoordY(0);
        doReturn(List.of(event)).when(world).queryForInquiryWeighted(eq(sid),eq(0),eq(0),anyInt(),anyInt());state.initialize(sid,pid);
    }
    @Test void playerInquiryWritesTheMappedRoleNotebookAndRewindRemovesLearnedInformation(){
        inquiry.processPlayerInquiry(sid,pid,0,0);assertEquals(1,knowledge.list(sid,god).size());assertTrue(knowledge.list(sid,pid).isEmpty());assertEquals("INQUIRED",knowledge.list(sid,god).get(0).get("sourceType"));state.restore(sid,0);assertTrue(knowledge.list(sid,god).isEmpty());
    }
    @Test void repeatedInquiryDoesNotAccumulateDuplicateMemoryOrNotebookEntries(){
        inquiry.processInquiry(sid,god,0,0);inquiry.processInquiry(sid,god,0,0);
        assertEquals(1,knowledge.list(sid,god).size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_memory WHERE session_id=?",Integer.class,sid));
        assertTrue(jdbc.queryForObject("SELECT state_changes FROM world_book WHERE session_id=? AND event_type='INQUIRY' ORDER BY entry_id DESC LIMIT 1",String.class,sid).contains("新增0"));
    }
    @Test void ownInquiryLogCannotRecursivelyBecomeNewIntelligence(){
        event.setEventType("INQUIRY");event.setActorIds(List.of(god));
        inquiry.processInquiry(sid,god,0,0);assertTrue(knowledge.list(sid,god).isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_memory WHERE session_id=?",Integer.class,sid));
    }
    @Test void foreignWorldEventsCannotEnterNotebook(){event.setSessionId("foreign");knowledge.rememberInquiry(sid,god,event);assertTrue(knowledge.list(sid,god).isEmpty());}
}
