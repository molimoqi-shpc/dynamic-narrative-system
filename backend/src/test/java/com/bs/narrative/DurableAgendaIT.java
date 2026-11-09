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

/** Real transactional game state. Model responses are controlled; no remote calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class DurableAgendaIT {
    @Autowired WorldTurnService turns; @Autowired SessionStateService state;
    @Autowired GameActionService actions;
    @Autowired InquiryService inquiries; @Autowired SceneGenerationService exploration; @Autowired InquiryService inquiry; @Autowired JdbcTemplate jdbc; @Autowired EntityManager em;
    @MockBean LLMProvider model;
    String sid="agenda_it",pid="agenda_user",role="agenda_player",npc="agenda_guard",other="agenda_other",a="agenda_a",b="agenda_b";
    static LLMResponse response(String j){return new LLMResponse(j,j,0,0,0,0);}
    @BeforeEach void seed(){
        for(var id:List.of(role,npc,other))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',75,'[]')",id,sid,id.equals(npc)?"巡守":id.equals(other)?"渡者":"你");
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'渡口',?,'渡口有人各忙其事。',0,0)",a,sid,"[\""+npc+"\",\""+other+"\"]");
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'云台','[]','云台静谧。',1,0)",b,sid);
        jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'NORTH','登云台')",sid,a,b);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'agenda_test','unused',?,?)",pid,role,a,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status,view_mode) VALUES(?,?,'PLAYING','DREAMER')",sid,pid);
        state.initialize(sid,pid);
        when(model.chatForJson(anyList())).thenReturn(response("{\"visible\":{\"lines\":[]}}"));
        when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"IGNORE\"}"));
    }
    WorldTurnService.PlannedStep travel(int delay){return new WorldTurnService.PlannedStep("LEAVE","",b,delay,"私下去云台交接守望");}
    void observe(){actions.processActionWithDb(new ActionRequest(pid,a,"观察周围",new ActionRequest.StructuredIntent("OBSERVE",role,"",null,null,1.0,"观察")),sid);}
    @Test void scheduledDepartureSurvivesTurnsExecutesOnceAndRewinds(){
        assertTrue(turns.schedule(sid,npc,a,travel(2)));
        observe();assertEquals(1,turns.tick(sid));assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
        turns.advance(sid,2,a,role);em.flush();assertEquals(2,turns.tick(sid));assertFalse(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,b).contains(npc));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='NPC_PLAN'",Integer.class,sid));
        state.restore(sid,1);assertEquals(1,turns.tick(sid));assertEquals("PLANNED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        turns.advance(sid,2,a,role);em.flush();assertEquals(2,turns.tick(sid));assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        state.restore(sid,0);assertEquals(0,turns.tick(sid));assertFalse(turns.hasPending(sid,npc));
    }
    @Test void waitingRunsCommittedPlanWithoutRepeatedActorCallsOrPrivateGoalLeak(){
        when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"LEAVE\",\"watchGodId\":\""+npc+"\"},\"visible\":{\"lines\":[]}}"));
        when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String u=msgs.get(1).get("content");if(u.contains("姓名：巡守") && u.contains("剩余0刻"))return response("{\"behavior\":\"LEAVE\",\"destination_scene_id\":\""+b+"\"}");return response(u.contains("姓名：巡守")?"{\"behavior\":\"IGNORE\",\"plan\":{\"behavior\":\"LEAVE\",\"after_ticks\":2,\"destination_scene_id\":\""+b+"\",\"purpose\":\"巡守独有的交接秘密\"}}":"{\"behavior\":\"IGNORE\"}");});
        var result=actions.processActionWithDb(new ActionRequest(pid,a,"等巡守离开",null),sid);
        assertEquals("READY",((Map<?,?>)result.ruleSnapshot().get("waiting")).get("status"));assertEquals(3L,result.ruleSnapshot().get("worldTick"));
        assertFalse(result.ruleSnapshot().get("publicEvents").toString().contains("交接秘密"));verify(model,times(5)).chatSubForJson(anyList());
        assertEquals("REVIEWED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
    }
    @Test void blockedRouteCannotTeleportOrComplete(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        jdbc.update("UPDATE scene_connection SET required_item='通行印' WHERE session_id=?",sid);em.clear();
        var result=turns.advance(sid,1,a,role);assertTrue(result.events().isEmpty());assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
    }
    @Test void offscreenPlansExecuteWithoutGivingPlayerRemoteKnowledge(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        var result=turns.advance(sid,1,"unobserved_scene",role);assertTrue(result.events().isEmpty());assertTrue(result.actedIds().contains(npc));
        var entries=jdbc.queryForList("SELECT actor_ids FROM world_book WHERE session_id=? AND event_type='NPC_PLAN'",String.class,sid);assertEquals(2,entries.size());assertTrue(entries.stream().noneMatch(e->e.contains(role)));
    }
    @Test void playerMovingAdvancesWorldAndSeesActualArrival(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        var result=actions.moveScene(pid,sid,"登云台");assertEquals(1,turns.tick(sid));assertTrue(result.factsChinese().stream().anyMatch(s->s.contains("巡守从渡口来到此地")));
        assertEquals(b,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        state.restore(sid,0);assertEquals(a,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals(0,turns.tick(sid));
    }
    @Test void explorationNarratesTheCastAfterScheduledArrivals(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        when(model.chatForJson(anyList())).thenReturn(response("{\"direction\":\"NORTH\",\"location_name\":\"云台\",\"visible\":{\"lines\":[]}}"));
        var result=exploration.explore(sid,pid,"我沿北方出口前往云台。");
        assertEquals(1L,result.get("worldTick"));assertTrue(((List<?>)result.get("presentNpcs")).contains(npc));assertTrue(result.get("facts").toString().contains("巡守从渡口来到此地"));assertEquals(1,turns.tick(sid));
    }
    @Test void askingAroundAdvancesOnceAndReturnsObservableScheduledChanges(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        String result=inquiry.processPlayerInquiry(sid,pid,0,0);
        assertEquals(1,turns.tick(sid));assertTrue(result.contains("巡守沿登云台离开"));assertFalse(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
    }
    @Test void schedulesCannotControlPlayerUseForeignActorsOrResetDeadlines(){
        assertFalse(turns.schedule(sid,role,a,travel(1)));assertFalse(turns.schedule("foreign",npc,a,travel(1)));assertFalse(turns.schedule(sid,npc,a,travel(0)));assertFalse(turns.schedule(sid,npc,a,travel(7)));
        assertFalse(turns.schedule(sid,npc,a,new WorldTurnService.PlannedStep("LEAVE","","made_up",1,"目的")));
        assertTrue(turns.schedule(sid,npc,a,travel(2)));assertFalse(turns.schedule(sid,npc,a,travel(6)));assertEquals(2L,jdbc.queryForObject("SELECT due_tick FROM npc_plan WHERE session_id=?",Long.class,sid));
    }
    @Test void inquiryIsPrivateAndTargetDisappearanceBlocksExecution(){
        var step=new WorldTurnService.PlannedStep("INQUIRE",other,"",1,"向渡者打听消息");assertTrue(turns.schedule(sid,npc,a,step));
        turns.advance(sid,1,a,role);assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(turns.schedule(sid,npc,a,step));jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?","[\""+npc+"\"]",a);em.clear();
        turns.advance(sid,2,a,role);em.flush();assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=? ORDER BY plan_id DESC LIMIT 1",String.class,sid));
    }
    @Test void modelFailureRetainsVisibleProposalWithoutInventingANewDecision(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));when(model.chatSubForJson(anyList())).thenThrow(new LLMApiException("offline",true));
        observe();assertEquals("PLANNED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
    }
    @Test void oldCheckpointWithoutNewTablesRestoresNoInventedPlans(){
        jdbc.update("UPDATE session_checkpoint SET payload=JSON_REMOVE(payload,'$.world_clock','$.npc_plan') WHERE session_id=? AND round_no=0",sid);
        assertTrue(turns.schedule(sid,npc,a,travel(1)));observe();state.restore(sid,0);assertEquals(0,turns.tick(sid));assertFalse(turns.hasPending(sid,npc));
    }
    @Test void cancelledPlanDoesNotFireAndCannotLeakIntoAnotherActorContext(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        assertFalse(turns.privateAgenda(sid,other).contains("交接守望"));
        turns.cancel(sid,npc,"现场风险改变，取消原安排");
        var result=turns.advance(sid,1,a,role);assertTrue(result.events().isEmpty());assertFalse(turns.hasPending(sid,npc));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
    }
    @Test void incapacitatedActorCannotExecuteAndKnowsWhyItsPlanFailed(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','实际身体状态','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,npc,a);em.clear();
        var result=turns.advance(sid,1,a,role);assertTrue(result.events().isEmpty());assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(turns.privateAgenda(sid,npc).contains("无法行动"));assertFalse(turns.privateAgenda(sid,other).contains("无法行动"));
    }
    @Test void invalidPlayerActionDoesNotSpendWorldTime(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));
        actions.processActionWithDb(new ActionRequest(pid,a,"无效操作",new ActionRequest.StructuredIntent("UNKNOWN",role,"",null,null,1.0,"无效")),sid);
        assertEquals(0,turns.tick(sid));assertTrue(turns.hasPending(sid,npc));
    }

    @Test void coordinateMovementStillExecutesDueNpcAgendaAndShowsArrival(){
        assertTrue(turns.schedule(sid,npc,a,new WorldTurnService.PlannedStep("LEAVE","",b,1,"巡守自主前往交接")));
        var result=actions.moveByCoord(pid,sid,1,0);
        assertEquals(1L,turns.tick(sid));assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(result.factsChinese().stream().anyMatch(v->v.contains("来到") || v.contains("离开")));
        assertEquals(b,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
    }
    @Test void inquiryAlsoAdvancesDueNpcAgendaInsteadOfFreezingWorld(){
        assertTrue(turns.schedule(sid,npc,a,new WorldTurnService.PlannedStep("LEAVE","",b,1,"巡守自主前往交接")));
        String result=inquiries.processPlayerInquiry(sid,pid,0,0);
        assertEquals(1L,turns.tick(sid));assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(result.contains("离开"));assertFalse(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
    }

    @Test void arrivalUsesNarratorOnlyAndCannotTurnLegacyNpcImpersonationIntoDialogue(){
        when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);assertTrue(msgs.get(0).get("content").contains("叙述者"));return response("{\"visible\":{\"lines\":[{\"godId\":\""+npc+"\",\"speech\":\"虚构马匹与对白\"}]}}" );});
        var r=actions.moveByCoord(pid,sid,1,0);
        assertTrue(r.lines().stream().allMatch(l->"narrator".equals(l.godId())));assertFalse(r.lines().toString().contains("虚构马匹"));
    }

    void physicalState(boolean awake,boolean bound){
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'现场状态',?,'SCENE_VIEW','',?,'渡口',0)",sid,npc,"view:"+a,"{\"awake\":"+awake+",\"bound\":"+bound+",\"playerKnown\":false,\"activity\":\"\",\"position\":\"渡口\",\"percepts\":[],\"hidden\":false}",a);
    }
    @Test void departureCannotExecuteAfterActorBecomesBound(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));physicalState(true,true);
        var result=turns.advance(sid,1,a,role);assertTrue(result.actedIds().isEmpty());
        assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,a).contains(npc));
        assertFalse(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,b).contains(npc));
    }
    @Test void sleepingActorCannotScheduleOrExecuteDeparture(){
        assertTrue(turns.schedule(sid,npc,a,travel(1)));physicalState(false,false);
        turns.advance(sid,1,a,role);assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=?",String.class,sid));
        assertFalse(turns.schedule(sid,npc,a,travel(2)));
    }
}
