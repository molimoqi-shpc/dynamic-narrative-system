package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.repository.TalentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class ModelBoundaryIT {
    @Autowired SceneGenerationService exploration;
    @Autowired CharacterCreationService creation;
    @Autowired SessionStateService state;
    @Autowired SessionNpcService npcs;
    @Autowired NpcAgentService npcDecisions;
    @Autowired WorldBookService worldBook;
    @Autowired TalentRepository talents;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockBean LLMProvider model;
    final String sid="boundary_test",pid="boundary_user",role="boundary_role",scene="boundary_scene";
    LLMResponse response(String json) {return new LLMResponse(json,json,0,0,0,0);}
    void seed() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui) VALUES(?,?,'测试角色','MORTAL','平静',75)",role,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'水岸','[]','初始场景',0,0)",scene,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'boundary_test','unused',?,?)",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);
        state.initialize(sid,pid);
    }
    @Test void malformedSceneCannotMovePlayerOrCommitAnyPartialTurn() {
        when(model.chatForJson(anyList())).thenReturn(response("not json"));
        assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();return exploration.explore(sid,pid,"向东走");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_session WHERE session_id=?",Integer.class,sid));
    }
    @Test void timeoutCannotInventAnUnrequestedMove() {
        when(model.chatForJson(anyList())).thenThrow(new LLMApiException("test timeout",true));
        assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();return exploration.explore(sid,pid,"穿过林间");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
    }
    @Test void emptyTalentOutputKeepsAnUnfinishedCreationWithoutInventingSelectableTalents() {
        var createdId=new java.util.concurrent.atomic.AtomicReference<String>();
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();
            when(model.chatForJson(anyList())).thenReturn(response("{}"));
            String created=(String)creation.startGame("p_demo","DREAMER").get("sessionId");
            createdId.set(created);
            CreationWorldFixture.ready(creation,model,created,"p_demo");
            assertThrows(BusinessException.class,()->creation.submitAnswers(created,"p_demo","一","二","三"));
            assertTrue(talents.findBySessionId(created).isEmpty());
            return null;
        });
        // The caller's repeatable-read snapshot predates the independent journal
        // commit. Verify durability after that caller transaction has rolled back.
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_reply_journal WHERE session_id=? AND stage='TALENTS'",Integer.class,createdId.get()));
        jdbc.update("DELETE FROM model_reply_journal WHERE session_id=?",createdId.get());
    }
    @Test void otherSessionCharactersCannotServeAsNpcTemplates() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_dou) VALUES('boundary_foreign','other_private','秘密角色','DEITY','平静',999)");
            assertTrue(npcs.resolve(sid,List.of("boundary_foreign")).isEmpty());return null;
        });
    }
    @Test void oversizedSceneFieldsAndReturnTripPreserveMapContinuity() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            String json="{\"direction\":\"EAST\",\"location_name\":\""+"地".repeat(100)+"\",\"exit_name\":\""+"路".repeat(100)+"\",\"scene_description\":\""+"景".repeat(10000)+"\"}";
            when(model.chatForJson(anyList())).thenReturn(response(json),response("{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"已到达\"}]}"),
                    response("{\"direction\":\"WEST\"}"),response("{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"已返回\"}]}"));
            var arrived=exploration.explore(sid,pid,"向东走");
            assertEquals(50,arrived.get("locationName").toString().length());
            assertEquals(8000,arrived.get("sceneDescription").toString().length());
            var returned=exploration.explore(sid,pid,"向西返回");assertEquals(scene,returned.get("sceneId"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));return null;
        });
    }
    @Test void crossSessionExitCannotCreateAnOrphanScene() {
        when(model.chatForJson(anyList())).thenReturn(response("{\"direction\":\"EAST\"}"));
        assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('boundary_foreign_scene','other_private','其他地图','[]','其他场景')");
            jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,'boundary_foreign_scene','EAST','东行')",sid,scene);
            return exploration.explore(sid,pid,"向东走");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE scene_id='boundary_foreign_scene'",Integer.class));
    }
    @Test void invalidNpcBehaviorFallsBackAndRepeatedNpcCannotActTwice() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            String json="{\""+role+"\":{\"behavior\":\""+"ATTACK".repeat(100)+"\",\"speech\":\"不合法\"}}";
            when(model.chatSubForJson(anyList())).thenReturn(response(json));
            var decisions=npcDecisions.decideAllNpcBehaviors(sid,List.of(role,role),"观察","水岸",List.of(role));
            assertEquals(1,decisions.size());assertEquals("IGNORE",decisions.get(0).behavior());
            assertTrue(decisions.get(0).usedFallback());return null;
        });
    }
    @Test void oversizedTalentAndBirthFieldsStillCreateAPlayableCharacter() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();
            String talent="{\"talents\":[{\"talent_name\":\""+"天".repeat(100)+"\",\"talent_description\":\"描述\",\"power_level\":999}]}";
            String birth="{\"birth_story\":\"故事\",\"story_time\":\""+"时".repeat(100)+"\",\"initial_location\":{\"location_name\":\""+"地".repeat(100)+"\",\"scene_description\":\"雾气弥漫的水岸，四周无人。\",\"present_npcs\":[]}}";
            when(model.chatForJson(anyList())).thenReturn(response(CreationWorldFixture.candidatesJson(talent)),response(birth),response("{}"));
            String created=(String)creation.startGame("p_demo","DREAMER").get("sessionId");
            CreationWorldFixture.ready(creation,model,created,"p_demo");
            creation.submitAnswers(created,"p_demo","一","二","三");
            var selected=talents.findBySessionId(created).get(0);assertEquals(64,selected.getTalentName().length());assertEquals(3,selected.getPowerLevel());
            creation.selectTalent(created,"p_demo",selected.getTalentId());
            assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,created));
            assertEquals(32,jdbc.queryForObject("SELECT CHAR_LENGTH(story_time) FROM world_book WHERE session_id=? LIMIT 1",Integer.class,created));return null;
        });
    }
    @Test void personalMemoryMatchesExactJsonActorAndOwnSession() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            jdbc.update("INSERT INTO world_book(session_id,round_no,location_name,event_type,event_description,actor_ids) VALUES(?,1,'水岸','ACTION','亲历','[\"boundary_role\"]')",sid);
            jdbc.update("INSERT INTO world_book(session_id,round_no,location_name,event_type,event_description,actor_ids) VALUES(?,2,'水岸','ACTION','旁人事件','[\"boundary_role_suffix\"]')",sid);
            jdbc.update("INSERT INTO world_book(session_id,round_no,location_name,event_type,event_description,actor_ids) VALUES('other_private',3,'水岸','ACTION','其他会话','[\"boundary_role\"]')");
            var known=worldBook.queryByCharacter(sid,role);
            assertEquals(1,known.size());assertEquals("亲历",known.get(0).getEventDescription());return null;
        });
    }
    @Test void groundedPurposeIsEstablishedWithinExistingDecisionAndNotRegenerated() {
        new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();seed();
            String known="渡者守着旧船。他已经没有饮水，手中有一枚暂时不用的通行印。";
            String proposal="{\"behavior\":\"IGNORE\",\"personal_goal\":\"换取饮水以继续留守旧船\",\"goal_basis\":\"他已经没有饮水\",\"goal_success\":\"实际取得饮水且仍能守船\",\"goal_tradeoff\":\"愿放弃暂时不用的通行印，不离开旧船\"}";
            when(model.chatSubForJson(anyList())).thenReturn(response(proposal));
            npcDecisions.decideNpcBehavior(sid,role,"观察",known,List.of(role));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=? AND status='ACTIVE' AND goal_description LIKE '%完成条件：%'",Integer.class,sid,role));
            npcDecisions.decideNpcBehavior(sid,role,"观察",known,List.of(role));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=? AND status='ACTIVE'",Integer.class,sid,role));
            verify(model,times(2)).chatSubForJson(anyList());return null;
        });
    }

}
