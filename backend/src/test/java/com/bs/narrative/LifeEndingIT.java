package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class LifeEndingIT {
 @Autowired ConversationOperationService operations;@Autowired SessionStateService checkpoints;
 @Autowired JdbcTemplate jdbc;@Autowired GameActionService game;@Autowired PlayerObjectiveService goals;
 @Autowired GameSessionService lifecycle;@Autowired StoryEndingService endings;
 @Autowired StoryObjectiveService storyGoals;
 @MockBean LLMProvider model;
 ObjectMapper json=new ObjectMapper();String sid,pid,role,scene;
 String mode="alive";boolean summaryFailure=false,malformed=false;int prose=0,archives=0,summaries=0,reviews=0;
 LLMResponse reply(Object value)throws Exception{String raw=json.writeValueAsString(value);return new LLMResponse(raw,raw,0,0,0,0);}
 LLMResponse finalReview()throws Exception{reviews++;var result=json.createObjectNode();result.putArray("paragraphRevisions");result.putArray("cast");result.putObject("timeProgress").putArray("milestones").addObject().putArray("elapsedPartsFromStart").addObject().put("amount",5).put("unit","MINUTE");return reply(result);}
 @BeforeEach void setup()throws Exception{
  for(String flag:List.of("sceneStory","storyFirst","storyArchive","manuscriptReview"))ReflectionTestUtils.setField(operations,flag,true);
  String suffix=UUID.randomUUID().toString().substring(0,8);sid="life_"+suffix;pid="life_u_"+suffix;role="life_p_"+suffix;scene="life_s_"+suffix;
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,'顾澄','MORTAL','平静','[]')",role,sid);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'渡口','[]','顾澄在渡口，洪水将至。')",scene,sid);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,?,'unused',?,?)",pid,role,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",sid,pid,role,scene);
  jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,location_name,event_type,actor_ids,event_description,importance) VALUES(?,0,'开篇','渡口','STORY_MILESTONE','[]','顾澄曾与父亲在渡口摆渡。',8)",sid);
  checkpoints.initialize(sid,pid);
  when(model.chatSubForJson(anyList())).thenAnswer(c->reply(Map.of("intention","自主行动")));
  when(model.chatForJson(anyList())).thenAnswer(call->{
   assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> messages=call.getArgument(0);String system=messages.get(0).get("content");
   if(system.contains("聚焦定稿阶段"))return finalReview();
   if(system.contains("【一生终章】")){
    summaries++;assertTrue(messages.get(1).get("content").contains("顾澄曾与父亲在渡口摆渡"));assertTrue(messages.get(1).get("content").contains("processed_dialog"));
    var context=json.readTree(messages.get(messages.size()-1).get("content")).path("endingContext");
    if(mode.equals("death")){assertEquals("DEATH",context.path("kind").asText());assertFalse(context.path("stillAlive").asBoolean());assertFalse(system.contains("本次唯一体裁：活着归隐的尾声"));}
    else {assertEquals("RETIREMENT",context.path("kind").asText());assertTrue(context.path("stillAlive").asBoolean());assertTrue(system.contains("主角此刻仍然活着"));assertFalse(system.contains("本次唯一体裁：确认死亡后的走马灯"));}
    if(summaryFailure)throw new LLMApiException("终章网络失败",false);
    if(malformed)return reply(Map.of("draft","missing summary"));
    return reply(Map.of("death_summary",mode.equals("death")?"顾澄想起与父亲摆渡的旧日，也想起最后的洪水。":"顾澄安顿在高坡，想起与父亲摆渡的旧日，此生终于有了归处。","final_evaluation","守住亲情，也承受了选择的代价。"));
   }
   if(system.contains("定稿归档阶段")){
    archives++;var a=json.createObjectNode();a.putArray("endingCast").addObject().put("roleId",role).put("sceneId",scene);
    var events=a.putArray("events");if(mode.equals("death"))events.addObject().put("kind","HEALTH").put("actorId",role).put("health",0);
    if(mode.equals("archive-invents"))a.putObject("lifeEnding").put("kind","RETIREMENT").put("playerRequested",true).put("fulfilled",true);
    a.put("continuity","本段已发生的结果。");return reply(a);
   }
   prose++;var draft=json.createObjectNode().put("story",mode.equals("death")?"顾澄最终没能挣脱洪水，死在渡口。":"顾澄来到高处，安顿下来。").put("stop","RESOLVED").put("elapsedSeconds",300);
   if(mode.equals("retire") || mode.equals("not-fulfilled"))draft.putObject("lifeEnding").put("kind","RETIREMENT").put("playerRequested",true).put("fulfilled",mode.equals("retire"));
   return reply(draft);
  });
 }
 Map<String,Object> act(String id){var s=game.describeScene(pid,sid);return operations.create(new ConversationOperationService.Request(id,sid,pid,s.get("sceneId").toString(),mode.equals("death")?"我尝试从洪水中逃生，接受真实风险。":"若能够安顿，我愿归隐终老，结束此生。",null,((Number)s.get("branchEpoch")).longValue(),((Number)s.get("revision")).longValue(),true));}
 @Test void completedOldTerminalGoalsDoNotEndALivingPlayer(){
  jdbc.update("INSERT INTO player_objective(objective_id,session_id,player_id,title,status,progress,current_step) VALUES('tiny',?,?,'阶段目标','COMPLETED',100,'已完成')",sid,pid);
  jdbc.update("INSERT INTO game_goal_rule(session_id,owner_id,goal_id,rule_json,terminal_flag) VALUES(?,?,'legacy',?,true)",sid,pid,"{\"type\":\"OBJECTIVE_COMPLETED\",\"objectiveId\":\"tiny\"}");
  assertEquals("COMPLETED",act("alive").get("status"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));assertEquals("ONGOING",goals.evaluateEnding(sid,pid,false).get("ending"));assertEquals(0,summaries);
 }
 @RepeatedTest(3) void actualDeathGeneratesOneWorldBookEpilogueAndReadReloadAreFree(){
  mode="death";assertEquals("COMPLETED",act("death").get("status"));assertEquals("DEAD",game.describeScene(pid,sid).get("sessionStatus"));assertEquals(0,game.describeScene(pid,sid).get("playerHealth"));
  assertEquals("DEFEAT",goals.evaluateEnding(sid,pid,true).get("ending"));assertTrue(lifecycle.processDeath(sid,pid).get("finalEvaluation").toString().contains("亲情"));
  operations.read(sid,pid,"death",0L);checkpoints.resume(sid,pid);assertEquals(1,summaries);assertEquals(1,prose);assertEquals(1,archives);assertEquals(1,reviews);
  assertTrue(game.describeScene(pid,sid).get("endingSummary").toString().contains("父亲"));
 }
 @Test void epilogueFailureKeepsDeathAndOnlyRetriesTheSummary(){
  mode="death";summaryFailure=true;assertEquals("PARTIAL",act("retry").get("status"));assertEquals("DEAD",game.describeScene(pid,sid).get("sessionStatus"));assertEquals(1,prose);assertEquals(1,archives);
  summaryFailure=false;assertEquals("COMPLETED",operations.recover(sid,pid,"retry",0).get("status"));assertEquals(1,prose);assertEquals(1,archives);assertEquals(2,summaries);assertEquals(1,reviews);
 }
 @Test void rawPaidEpilogueCanBeReusedAfterFormatRepair()throws Exception{
  mode="death";malformed=true;assertEquals("PARTIAL",act("raw").get("status"));
  var p=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='raw'",String.class,sid));assertTrue(p.path("lifeEndingRaw").asText().contains("missing summary"));
  // Simulate an already received valid reply: recovering must deserialize it, not call again.
  jdbc.update("UPDATE game_operation SET payload=JSON_SET(payload,'$.lifeEndingRaw',?) WHERE session_id=? AND operation_id='raw'",json.writeValueAsString(Map.of("death_summary","已付费终章","final_evaluation","已付费评价")),sid);
  assertEquals("COMPLETED",operations.recover(sid,pid,"raw",0).get("status"));assertEquals(1,summaries);assertEquals("已付费终章",endings.read(sid).get("endingSummary"));
 }
 @Test void fulfilledVoluntaryRetirementIsDistinctFromDeathAndRewindRestoresLife(){
  mode="retire";assertEquals("COMPLETED",act("retire").get("status"));assertEquals("RETIRED",game.describeScene(pid,sid).get("sessionStatus"));assertFalse(lifecycle.checkDeath(sid,pid));assertEquals("RETIREMENT",goals.evaluateEnding(sid,pid,false).get("ending"));
  checkpoints.restore(sid,0);assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));assertTrue(endings.read(sid).isEmpty());assertEquals(100,game.describeScene(pid,sid).get("playerHealth"));
  assertEquals("COMPLETED",act("after-rewind").get("status"));
 }
 @Test void unfulfilledRetirementDoesNotEndTheWorld(){mode="not-fulfilled";assertEquals("COMPLETED",act("blocked").get("status"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));assertEquals(0,summaries);}
 @Test void archiverCannotInventRetirement(){mode="archive-invents";assertEquals("COMPLETED",act("archiver").get("status"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));assertEquals(0,summaries);}
 @Test void storyObjectivesUseSubstantialNarrativeAimsWithoutTerminalRules()throws Exception{
  ReflectionTestUtils.setField(goals,"narrativeGoals",true);
  doReturn(reply(Map.of("objectives",List.of(Map.of("objective_id","rescue","title","带母亲脱险并安顿","current_step","洪水将至，尚未离开渡口"))))).when(model).chatForJson(anyList());
  assertEquals(1,goals.initObjectives(sid,pid,"顾澄在渡口准备带母亲撤离。","识水").size());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_goal_rule WHERE session_id=?",Integer.class,sid));
  var updates=json.createArrayNode();updates.addObject().put("objectiveId","rescue").put("status","COMPLETED").put("currentStep","已在高坡安顿。");updates.addObject().put("objectiveId","foreign").put("status","COMPLETED");storyGoals.apply(sid,pid,updates);
  assertEquals("COMPLETED",goals.listObjectives(pid,sid).get(0).getStatus());goals.markSessionVictory(sid);assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
 }
 @Test void archiveReceivesActualObjectivesAndRecordsProgressWithoutExtraDialogueLogs()throws Exception{
  jdbc.update("INSERT INTO player_objective(objective_id,session_id,player_id,title,status,progress,current_step) VALUES('hear',?,?,'听母亲解释','ACTIVE',0,'尚未听到')",sid,pid);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("聚焦定稿阶段"))return finalReview();
   if(m.get(0).get("content").contains("定稿归档阶段")){
    var in=json.readTree(m.get(1).get("content"));assertEquals("hear",in.at("/before/playerObjectives/0/objectiveId").asText());
    var a=json.createObjectNode();a.putArray("events");a.putArray("objectiveUpdates").addObject().put("objectiveId","hear").put("status","COMPLETED").put("currentStep","已经听完解释。");return reply(a);
   }return reply(Map.of("story","顾澄听完了母亲的解释。","stop","RESOLVED","elapsedSeconds",60));
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",act("progress").get("status"));assertEquals("COMPLETED",goals.listObjectives(pid,sid).get(0).getStatus());assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
  checkpoints.restore(sid,0);assertTrue(goals.listObjectives(pid,sid).isEmpty());
 }
 @Test void longLifeEndingUsesBoundedSourcesWithoutDeletingOriginals(){
  String prose="平凡日常。".repeat(2400);
  for(int n=1;n<=120;n++){
   jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,?,'继续生活','{}','NO_OP',?,'{}')",sid,pid,scene,n,"第"+n+"章开头。"+prose+"第"+n+"章结尾。");
   jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,location_name,event_type,actor_ids,event_description,importance) VALUES(?,?,'往事','渡口','STORY_MILESTONE','[]',?,?)",sid,n,n==60?"中年时救下父亲，父亲此后健在。":prose,n==60?10:2);
  }
  var facts=endings.facts(sid);String text=facts.toString();
  assertEquals(120,facts.at("/sourceSelection/totalChapters").asInt());assertEquals(6,facts.path("publishedChapters").size());
  assertTrue(text.contains("中年时救下父亲"));assertTrue(text.contains("第1章开头"));assertTrue(text.contains("第120章结尾"));
  assertTrue(text.length()<140000,"A long life must fit the ending model's input budget");
  assertTrue(text.contains("excerpted"));assertEquals(120,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
  assertEquals("第60章开头。"+prose+"第60章结尾。",jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=? AND round_no=60",String.class,sid));
 }
 @AfterEach void cleanup(){
  ReflectionTestUtils.setField(goals,"narrativeGoals",false);
  for(String flag:List.of("sceneStory","storyFirst","storyArchive","manuscriptReview"))ReflectionTestUtils.setField(operations,flag,false);
  for(String table:List.of("llm_operation_usage","story_foreshadow","story_plot_event","session_checkpoint_archive","game_goal_rule","game_operation","game_runtime","session_checkpoint","npc_decision_log","npc_memory","npc_goal","player_memory","player_objective","story_summary","interaction_log","world_book","god_relationship","scene_connection","session_npc_alias","npc_plan","world_clock","role_knowledge"))jdbc.update("DELETE FROM "+table+" WHERE session_id=?",sid);
  jdbc.update("DELETE FROM player_profile WHERE player_id=?",pid);jdbc.update("DELETE FROM game_session WHERE session_id=?",sid);jdbc.update("DELETE FROM game_scene WHERE session_id=?",sid);jdbc.update("DELETE FROM god_profile WHERE session_id=?",sid);
 }
}
