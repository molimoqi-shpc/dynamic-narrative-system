package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class NpcGoalLoopIT {
 @Autowired NpcGoalService goals; @Autowired JdbcTemplate jdbc; @Autowired EntityManager em;
 @Autowired WorldTurnService turns; @Autowired SessionStateService state; @Autowired NpcAgentService actors;
 @MockBean LLMProvider model;
 String sid="goal_loop_it",npc="goal_loop_npc",role="goal_loop_role",pid="goal_loop_user",scene="goal_loop_scene";
 String known="渡者守着旧船。水囊可以用于补充饮水，通行印暂时不用。当前位于试验渡口，东面是旧桥。";
 String goal="换取水囊并继续留守渡口"; String success="持有「水囊」且位于「试验渡口」";
 com.fasterxml.jackson.databind.JsonNode checks(String j){return ModelOutput.object("{\"c\":"+j+"}").get("c");}
 com.fasterxml.jackson.databind.JsonNode contract(){return checks("[{\"type\":\"HAS_ITEM\",\"value\":\"水囊\"},{\"type\":\"AT_LOCATION\",\"value\":\"试验渡口\"}]");}
 @BeforeEach void seed(){
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,'渡者','MORTAL','平静',75,'[\"通行印\"]'),(?,?,'你','MORTAL','平静',75,'[\"水囊\"]')",npc,sid,role,sid);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'试验渡口',?, ?,0,0),('goal_loop_bridge',?,'旧桥','[]','旧桥',1,0)",scene,sid,"[\""+npc+"\"]",known,sid);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'goal_loop_test','unused',?,?,'[\"水囊\"]')",pid,role,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,status,player_god_id,current_scene_id) VALUES(?,?,'PLAYING',?,?)",sid,pid,role,scene);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,'goal_loop_bridge','EAST','旧桥小路')",sid,scene);
  goals.initPresetGoals(sid,npc,"渡者");assertTrue(goals.adoptGroundedGoal(sid,npc,goal,"渡者守着旧船",success,"可以交换通行印，不离开渡口",known,contract()));
  em.flush();state.initialize(sid,pid);
 }
 String status(){em.flush();return jdbc.queryForObject("SELECT status FROM npc_goal WHERE session_id=? AND god_id=? ORDER BY goal_id DESC LIMIT 1",String.class,sid,npc);}
 void water(){jdbc.update("UPDATE god_profile SET inventory='[\"水囊\"]' WHERE god_id=?",npc);}
 @Test void ownershipAndAllConditionsMustReallyHold(){
  goals.refreshProgress(sid,npc);assertEquals("ACTIVE",status());water();
  jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE scene_id=?",scene);
  goals.refreshProgress(sid,npc);assertEquals("ACTIVE",status());
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?","[\""+npc+"\"]",scene);
  goals.refreshProgress(sid,npc);assertEquals("COMPLETED",status());
 }
 @Test void blockedStepFeedsStrategyWithoutLosingOrCompletingTheGoal(){
  jdbc.update("INSERT INTO npc_plan(session_id,god_id,origin_scene_id,behavior,purpose,created_tick,due_tick,status,result) VALUES(?,?,?,'LEAVE','寻找可交换对象',0,1,'BLOCKED','原定路线当前无法通行')",sid,npc,scene);
  goals.refreshProgress(sid,npc);assertEquals("ACTIVE",status());
  String own=String.join(" ",goals.getGoalDescriptions(sid,npc));assertTrue(own.contains("原定路线当前无法通行"));assertTrue(own.contains("换可执行策略"));
  assertTrue(goals.getGoalDescriptions(sid,role).isEmpty());
 }
 @Test void zeroGuiDoesNotFailGoalsAndPlainTextCannotClaimSuccess(){
  goals.setGoal(sid,npc,"核查复杂旧案，仍缺证明",5);water();
  goals.refreshProgress(sid,npc);em.flush();
  assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM npc_goal WHERE session_id=? AND goal_description LIKE '核查复杂%'",String.class,sid));
  jdbc.update("UPDATE god_profile SET base_gui=0 WHERE god_id=?",npc);goals.refreshProgress(sid,npc);em.flush();
  assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND status='ACTIVE'",Integer.class,sid)>0);
 }
 @Test void checkpointRewindsCompletionInventoryAndFeedbackTogether(){
  water();state.complete(sid,"取得水囊",false);assertEquals("COMPLETED",status());
  state.restore(sid,0);assertEquals("ACTIVE",status());
  assertTrue(jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,npc).contains("通行印"));
  assertFalse(String.join("",goals.getGoalDescriptions(sid,npc)).contains("已完成："));
 }
 @Test void completedPurposeBecomesPrivateHistoryAndActorCanEstablishNextGoal(){
  water();goals.refreshProgress(sid,npc);assertEquals("COMPLETED",status());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{
   List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(1).get("content");
   assertTrue(prompt.contains("过往目的·COMPLETED"));assertTrue(prompt.contains("待确立个人目标"));
   String j="{\"behavior\":\"IGNORE\",\"personal_goal\":\"前往旧桥查看通行情况\",\"goal_basis\":\"东面是旧桥\",\"goal_success\":\"位于「旧桥」\",\"goal_tradeoff\":\"愿暂时放下旧船，不放弃水囊\",\"goal_checks\":[{\"type\":\"AT_LOCATION\",\"value\":\"旧桥\"}],\"plan\":{\"behavior\":\"LEAVE\",\"after_ticks\":1,\"destination_scene_id\":\"goal_loop_bridge\",\"purpose\":\"实地核对旧桥通行\"}}";
   return new LLMResponse(j,j,0,0,0,0);
  });
  var decision=actors.decideNpcBehavior(sid,npc,"观察",known,List.of(npc,role));assertEquals("ACTIVE",status());
  em.flush();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND status='COMPLETED'",Integer.class,sid));
  assertTrue(turns.schedule(sid,npc,scene,decision.plannedStep()));
  var step=turns.advance(sid,1,scene,role);assertTrue(step.actedIds().contains(npc));
  goals.refreshProgress(sid,npc);assertEquals("COMPLETED",status());
  verify(model,times(1)).chatSubForJson(anyList());
 }
 @Test void inventedOrWeakenedConditionsAndAlreadySatisfiedGoalsAreRejected(){
  goals.initPresetGoals(sid,role,"你");
  assertFalse(goals.adoptGroundedGoal(sid,role,goal,"渡者守着旧船",success,"可以交换通行印，不离开渡口",known,checks("[{\"type\":\"HAS_ITEM\",\"value\":\"水囊\"}]")));
  assertFalse(goals.adoptGroundedGoal(sid,role,goal,"渡者守着旧船","持有「水囊」","可以交换通行印，不离开渡口",known,checks("[{\"type\":\"HAS_ITEM\",\"value\":\"水囊\"}]")));
  assertFalse(goals.adoptGroundedGoal(sid,role,goal,"渡者守着旧船","位于「远方神殿」","可以交换通行印，不离开渡口",known,checks("[{\"type\":\"AT_LOCATION\",\"value\":\"远方神殿\"}]")));
 }

 @Test void aPendingPlanDoesNotSilenceNewActualWordsAddressedToTheRole(){
  jdbc.update("INSERT INTO npc_plan(session_id,god_id,origin_scene_id,behavior,purpose,created_tick,due_tick,status) VALUES(?,?,?,'INQUIRE','查看旧船情况',0,20,'PLANNED')",sid,npc,scene);
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);assertTrue(msgs.get(1).get("content").contains("【玩家确实向你说出的话】你需要什么？"));String j="{\"behavior\":\"RESPOND\",\"target_god_id\":\""+role+"\",\"speech\":\"我需要水囊，旧船也还要照看。\"}";return new LLMResponse(j,j,0,0,0,0);});
  var decision=actors.decideNpcBehavior(sid,npc,"没有新增公开玩家行为。\n【玩家确实向你说出的话】你需要什么？",known,List.of(npc,role));
  assertEquals("RESPOND",decision.behavior());assertTrue(decision.speech().contains("水囊"));verify(model,times(1)).chatSubForJson(anyList());
 }

 @Test void negativeGuiStillAllowsRealObjectiveCompletion(){
  jdbc.update("UPDATE god_profile SET base_gui=-5 WHERE god_id=?",npc);water();goals.refreshProgress(sid,npc);assertEquals("COMPLETED",status());
 }

}
