package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.dto.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Cross-layer regressions: clocks, independent psychology, delivered speech, effects, narration and rewind. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class CausalTurnLifecycleIT {
 @Autowired GameTranscriptService transcript; @Autowired GameActionService actions;@Autowired SessionStateService checkpoints;@Autowired JdbcTemplate jdbc;
 @Autowired OpenSceneService physical;@Autowired TemporalContextService temporal;@Autowired KnowledgeService knowledge;
 @Autowired OperationEffectService operationEffects;@Autowired ObjectiveRuleService objectiveRules;@Autowired WorldTurnService turns;
 @Autowired GodProfileRepository gods;@Autowired GameSceneRepository scenes;
 @MockBean LLMProvider model;
 final String sid="causal_turn_it",pid="causal_user",player="causal_player",a="causal_a",b="causal_b",c="causal_c",scene="causal_scene";
 final String question="这道绳结以前解开过吗？",answer="我还没检查过，不知道是否能解开。",result="工匠甲检查了绳结，确认绳结仍卡在石柱后方，检查已经结束。";
 final ObjectMapper json=new ObjectMapper();List<String> prompts=Collections.synchronizedList(new ArrayList<>());AtomicInteger physicalCalls=new AtomicInteger();
 LLMResponse r(Map<String,Object> map)throws Exception{String j=json.writeValueAsString(map);return new LLMResponse(j,j,0,0,0,0);}
 @BeforeEach void seed(){
  for(String id:List.of(player,a,b,c))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',15,15,15,15,25,75,'[]')",id,sid,id.equals(player)?"你":id.equals(a)?"工匠甲":id.equals(b)?"守望者乙":"旁观者丙");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'石柱边',?,'工匠甲准备检查石柱后的绳结，尚未检查。守望者乙和旁观者丙站在附近，均清醒且未被束缚。绳结实际存在。',0,0)",scene,sid,"[\""+a+"\",\""+b+"\",\""+c+"\"]");
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'causal_test','unused',?,?,'[]')",pid,player,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,status,view_mode) VALUES(?,?,?,'PLAYING','DREAMER')",sid,pid,player);checkpoints.initialize(sid,pid);
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String system=msgs.get(0).get("content");
   if(system.contains("现场行动裁定者")){physicalCalls.incrementAndGet();assertTrue(msgs.get(1).get("content").contains("worldTick"));return r(Map.of("events",List.of(Map.of("actorId",a,"text",result,"view",Map.of("awake",true,"bound",false,"activity","检查已经结束，绳结仍卡在石柱后方","percepts",List.of("绳结位于石柱后方")))),"progressed",true,"progressBasis","检查已经结束"));}
   if(system.contains("意图解析器"))return r(Map.of("intent","WAIT","waiting",Map.of("condition","CHANGE")));
   return r(Map.of("visible",Map.of("lines",List.of(Map.of("godId","narrator","speech","此处的变化按实际记录继续。")),"bridges",Map.of())));
  });
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");prompts.add(prompt);
   var time=Pattern.compile("generatedTick=(\\d+)").matcher(prompt);assertTrue(time.find());long tick=Long.parseLong(time.group(1));
   if(prompt.contains("姓名：工匠甲") && tick==1)return r(Map.of("behavior","RESPOND","target_god_id",b,"speech",question,"inner_thought","不向旁人透露的顾虑甲","action_text","检查已有石柱后的绳结位置","decision_point","NONE"));
   if(prompt.contains("姓名：守望者乙")){
    if(tick==1){assertTrue(prompt.contains(question),"recipient must hear a same-tick earlier question");assertFalse(prompt.contains("不向旁人透露的顾虑甲"));assertFalse(prompt.contains(result),"physical outcome has not happened yet");return r(Map.of("behavior","RESPOND","target_god_id",a,"speech",answer,"decision_point","NONE"));}
    assertTrue(prompt.contains("当前仍有效的现场状态"));assertTrue(prompt.contains("检查已经结束，绳结仍卡在石柱后方"));
    return r(Map.of("behavior","RESPOND","target_god_id",player,"speech","此前检查已经完成，绳结仍在石柱后方。","decision_point","NONE"));
   }
   if(prompt.contains("姓名：旁观者丙") && tick==1)assertFalse(prompt.contains(answer),"same-cohort peer has not heard its concurrently generated answer");
   return r(Map.of("behavior","IGNORE"));
  });
 }
 ActionResponse waitForChange(){return actions.processActionWithDb(new ActionRequest(pid,scene,"隐匿自身，继续等到有变化",null),sid);}
 @Test void oneTickCanContainARealQuestionThenAnInformedAnswerBeforeThePhysicalResult(){
  var response=waitForChange();String story=response.lines().stream().filter(l->l.godId().equals("narrator")).findFirst().orElseThrow().speech();
  assertTrue(story.indexOf(question)<story.indexOf(answer));assertTrue(story.indexOf(answer)<story.indexOf(result));
  var entries=(List<Map<String,Object>>)response.ruleSnapshot().get("temporalEvents");
  var q=entries.stream().filter(e->question.equals(e.get("text"))).findFirst().orElseThrow();var replied=entries.stream().filter(e->answer.equals(e.get("text"))).findFirst().orElseThrow();
  assertEquals(q.get("generatedTick"),replied.get("generatedTick"));assertTrue(((Number)q.get("sequence")).intValue()<((Number)replied.get("sequence")).intValue());
  var ownB=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_DECISION'",String.class,sid,b);
  assertTrue(ownB.stream().anyMatch(s->ModelOutput.object(s).path("observedThroughSequence").asInt()>=((Number)q.get("sequence")).intValue()));
  assertFalse(temporal.roleContext(sid,b).contains("不向旁人透露的顾虑甲"));assertTrue(temporal.roleContext(sid,a).contains("不向旁人透露的顾虑甲"));
  assertTrue(knowledge.list(sid,player).stream().noneMatch(k->Set.of("TURN_EVENT","ROLE_DECISION").contains(k.get("sourceType"))));
 }
 @Test void tenConsecutiveTurnsKeepCompletedResultsWhileTheClockAndSpeechAdvance(){
  waitForChange();for(int n=2;n<=10;n++){
   String text="请说明现在的绳结状态，第"+n+"次询问。";var response=actions.processActionWithDb(new ActionRequest(pid,scene,text,null,new ActionRequest.DialogueInput(b,text)),sid);
   assertEquals((long)n,temporal.tick(sid));assertEquals("SPOKEN",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
   var state=physical.view(sid,gods.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow());assertEquals(1L,((Number)state.get("updatedAtTick")).longValue());assertEquals("检查已经结束，绳结仍卡在石柱后方",state.get("activity"));
   assertTrue(response.lines().stream().filter(l->l.godId().equals("narrator")).anyMatch(l->l.speech().contains("此前检查已经完成")));
  }
  assertEquals(1,physicalCalls.get(),"a completed check must not be executed again");
  assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
  checkpoints.restore(sid,0);assertEquals(0L,temporal.tick(sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type IN ('TURN_EVENT','ROLE_DECISION')",Integer.class,sid));
  assertEquals("",physical.view(sid,gods.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
 }
 @Test void anUnchangedOlderStateRemainsCurrentRatherThanBecomingAHistoryEvent(){
  waitForChange();var before=physical.physicalStates(sid,scenes.findById(scene).orElseThrow());assertEquals(1L,((Number)before.stream().filter(s->a.equals(s.get("id"))).findFirst().orElseThrow().get("updatedAtTick")).longValue());
  jdbc.update("UPDATE world_clock SET tick=8 WHERE session_id=?",sid);var now=physical.physicalStates(sid,scenes.findById(scene).orElseThrow()).stream().filter(s->a.equals(s.get("id"))).findFirst().orElseThrow();
  assertEquals(1L,((Number)now.get("updatedAtTick")).longValue());assertEquals(8L,now.get("validAtTick"));assertTrue(temporal.roleContext(sid,b).contains("历史："));assertEquals("检查已经结束，绳结仍卡在石柱后方",now.get("activity"));
 }

 @Test void anotherCharactersUnconsciousnessAndRestraintDoNotInitializeEveryoneThatWay(){
  var target=scenes.findById(scene).orElseThrow();target.setSceneSummary("工匠甲昏迷，旁观者丙被捆在柱上，守望者乙站在旁边。");scenes.saveAndFlush(target);
  assertEquals(false,physical.view(sid,gods.findById(a).orElseThrow(),target).get("awake"));
  assertEquals(true,physical.view(sid,gods.findById(c).orElseThrow(),target).get("bound"));
  var innocent=physical.view(sid,gods.findById(b).orElseThrow(),target);assertEquals(true,innocent.get("awake"));assertEquals(false,innocent.get("bound"));
 }
 @Test void aPartialLegacyViewRetainsItsConfirmedTransitionAndGetsSafeMissingFields(){
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧身体状态','{\"awake\":true,\"bound\":false}','SCENE_VIEW','',?,'石柱边',0)",sid,a,"view:"+scene,scene);
  var current=physical.physicalStates(sid,scenes.findById(scene).orElseThrow());assertEquals("",current.stream().filter(v->a.equals(v.get("id"))).findFirst().orElseThrow().get("activity"));
 }

 @Test void executedFacilityStatePersistsAfterItsActorLeavesAndRewindRestoresTheOpening(){
  var target=scenes.findById(scene).orElseThrow();String text="工匠甲拆开了绳结，绳结已解开。";
  var actual=json.createObjectNode();actual.putArray("events").addObject().put("actorId",a).put("text",text);
  actual.putArray("sceneChanges").addObject().put("objectName","绳结").put("state","已解开").put("basis",text);
  jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,1)",sid);
  temporal.validateSceneChanges(sid,target,actual,List.of("工匠甲"),List.of());temporal.applySceneChanges(sid,player,target,actual);
  target.setPresentGods(List.of(b,c));scenes.saveAndFlush(target);jdbc.update("UPDATE world_clock SET tick=8 WHERE session_id=?",sid);
  temporal.applySceneChanges(sid,player,target,actual);var record=temporal.sceneState(sid,scene).get(0);
  assertEquals("已解开",record.get("state"));assertEquals(1L,((Number)record.get("updatedAtTick")).longValue());
  assertTrue(knowledge.list(sid,player).stream().noneMatch(k->"SCENE_STATE".equals(k.get("sourceType"))));
  checkpoints.restore(sid,0);assertTrue(temporal.sceneState(sid,scene).isEmpty());
 }
 @Test void futurePlansAndInventedFacilitiesCannotBecomeActualSceneState(){
  var actual=json.createObjectNode();actual.putArray("events").addObject().put("actorId",a).put("text","工匠甲准备拆开绳结。");
  var change=actual.putArray("sceneChanges").addObject().put("objectName","绳结").put("state","准备拆开").put("basis","工匠甲准备拆开绳结。");
  assertThrows(IllegalArgumentException.class,()->temporal.validateSceneChanges(sid,scenes.findById(scene).orElseThrow(),actual,List.of(),List.of()));
  change.put("objectName","密室机关").put("state","已打开").put("basis","密室机关已打开。");
  actual.withArray("events").removeAll().addObject().put("actorId",a).put("text","密室机关已打开。");
  assertThrows(IllegalArgumentException.class,()->temporal.validateSceneChanges(sid,scenes.findById(scene).orElseThrow(),actual,List.of(),List.of()));
 }
 void pendingDeparture(){
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES('causal_outside',?,'门外','[]','门外',1,0)",sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,'causal_outside','EAST','门外小径')",sid,scene);
  jdbc.update("INSERT INTO npc_plan(session_id,god_id,origin_scene_id,behavior,destination_scene_id,purpose,created_tick,due_tick,status) VALUES(?,?,?,'LEAVE','causal_outside','去门外查看',0,1,'PLANNED')",sid,a,scene);
 }
 @Test void aDuePlanCannotMakeTheRecipientLeaveBeforeHearingThePlayersNewWords(){
  pendingDeparture();String words="暂时别走，我想问问绳结的位置。";
  doAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");if(prompt.contains("姓名：工匠甲")){assertTrue(prompt.contains(words));assertTrue(prompt.contains("causal_outside"));return r(Map.of("behavior","IGNORE","inner_thought","先把眼前的问题考虑清楚，暂不出门。"));}return r(Map.of("behavior","IGNORE"));}).when(model).chatSubForJson(anyList());
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,words,null,new ActionRequest.DialogueInput(a,words)),sid);
  assertTrue(scenes.findById(scene).orElseThrow().getPresentGods().contains(a));assertEquals("REVIEWED",jdbc.queryForObject("SELECT status FROM npc_plan WHERE session_id=? AND god_id=?",String.class,sid,a));
  assertFalse(response.factsChinese().stream().anyMatch(t->t.contains("工匠甲离开当前场景")));assertEquals("NO_REPLY",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
 }
 @Test void aRoleCanStillFreelyExecuteItsOldPlanWhenItActuallyChoosesThatAction(){
  pendingDeparture();doAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);if(msgs.get(1).get("content").contains("姓名：工匠甲"))return r(Map.of("behavior","LEAVE","destination_scene_id","causal_outside","speech","我先去门外看看。"));return r(Map.of("behavior","IGNORE"));}).when(model).chatSubForJson(anyList());
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"观察",new ActionRequest.StructuredIntent("OBSERVE",player,"",null,null,1.0,"观察")),sid);
  assertFalse(scenes.findById(scene).orElseThrow().getPresentGods().contains(a));assertTrue(scenes.findById("causal_outside").orElseThrow().getPresentGods().contains(a));
  assertTrue(response.factsChinese().stream().anyMatch(t->t.contains("工匠甲离开当前场景")));
 }
 @Test void technicalFailureDoesNotBecomeSilenceAndSurvivesTranscriptReload(){
  doThrow(new LLMApiException("test unavailable",false)).when(model).chatSubForJson(anyList());
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"能借工具吗？",null,new ActionRequest.DialogueInput(b,"能借工具吗？")),sid);
  assertEquals("UNAVAILABLE",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
  assertFalse(((List<?>)response.ruleSnapshot().get("technicalIssues")).isEmpty());
  assertTrue(response.npcDecisions().stream().allMatch(d->d.innerThought().isBlank() && d.speech().isBlank()));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_DECISION'",Integer.class,sid));
  var saved=jdbc.queryForObject("SELECT JSON_EXTRACT(rule_snapshot,'$.technicalIssues') FROM interaction_log WHERE session_id=?",String.class,sid);
  assertTrue(saved.contains("ROLE_DECISION_UNAVAILABLE"));
  var history=(List<Map<String,Object>>)transcript.read(sid,pid,null).get("turns");
  assertFalse(((List<?>)history.get(0).get("technicalIssues")).isEmpty());
 }
 @Test void speakingToSomeoneElseDoesNotAnswerOrDirectlyDeliverToPlayer(){
  doAnswer(call->{
   List<Map<String,String>> messages=call.getArgument(0);
   return messages.get(1).get("content").contains("姓名：守望者乙")
    ?r(Map.of("behavior","RESPOND","target_god_id",a,"speech","工匠，你先整理工具。","decision_point","NONE"))
    :r(Map.of("behavior","IGNORE"));
  }).when(model).chatSubForJson(anyList());
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"能借工具吗？",null,new ActionRequest.DialogueInput(b,"能借工具吗？")),sid);
  assertEquals("OTHER_ADDRESSEE",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
 }
 void facilityEffect(String object,String before,String after){
  var location=scenes.findById(scene).orElseThrow();location.setSceneSummary(location.getSceneSummary()+"；已有设施Aa、BB、aa。");scenes.saveAndFlush(location);
  var packet=json.valueToTree(Map.of("process","现场设施发生实际变化。","durationSeconds",1,"status","COMPLETED","effects",List.of(Map.of("kind","FACILITY","objectName",object,"before",before,"after",after))));
  operationEffects.apply(sid,location,a,player,packet,new HashSet<>());
 }
 void legacyFacility(String owner,String ref,String object,String state,long tick)throws Exception{
  String content=json.writeValueAsString(Map.of("objectName",object,"state",state,"sceneId",scene,"updatedAtTick",tick));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧设施状态',?,'SCENE_STATE','',?,'石柱边',?)",sid,owner,ref,content,scene,tick);
 }
 boolean facilityGoal(String object,String state){return objectiveRules.matches(sid,pid,json.valueToTree(Map.of("type","FACILITY_STATE","sceneId",scene,"objectName",object,"state",state)));}
 @Test void facilitiesWithTheSameJavaHashCannotOverwriteEachOther(){
  assertEquals("Aa".hashCode(),"BB".hashCode());facilityEffect("Aa","","第一设施已打开");facilityEffect("BB","","第二设施已关闭");
  assertEquals(2,temporal.sceneState(sid,scene).size());assertTrue(facilityGoal("Aa","第一设施已打开"));assertTrue(facilityGoal("BB","第二设施已关闭"));
  facilityEffect("Aa","第一设施已打开","第一设施已关闭");assertTrue(facilityGoal("BB","第二设施已关闭"));assertFalse(facilityGoal("Aa","第一设施已打开"));
 }
 @Test void oldAndNewFacilityWritersContinueTheSameObject()throws Exception{
  legacyFacility(player,"scene_state:"+scene+":old-short","绳结","系紧",0);
  facilityEffect("绳结","系紧","已松开");var result=json.createObjectNode();result.putArray("sceneChanges").addObject().put("objectName","绳结").put("state","重新系紧").put("basis","绳结重新系紧。");
  temporal.applySceneChanges(sid,a,scenes.findById(scene).orElseThrow(),result);facilityEffect("绳结","重新系紧","已解开");
  assertEquals(1,temporal.sceneState(sid,scene).size());assertTrue(facilityGoal("绳结","已解开"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
 }
 @Test void differentCallersCannotCreateCompetingCurrentFacilityCopies(){
  var location=scenes.findById(scene).orElseThrow();temporal.writeFacility(sid,player,location,"绳结","系紧","实际系紧");temporal.writeFacility(sid,a,location,"绳结","松开","实际松开");
  assertEquals(1,temporal.sceneState(sid,scene).size());assertTrue(facilityGoal("绳结","松开"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
 }
 @Test void staleFacilityCopiesDoNotCompleteGoalsAndAreSynchronizedOnNextWrite()throws Exception{
  legacyFacility(player,"old-copy","绳结","已解开",1);legacyFacility(a,"new-copy","绳结","重新系紧",2);
  assertFalse(facilityGoal("绳结","已解开"));assertTrue(facilityGoal("绳结","重新系紧"));assertEquals(1,temporal.sceneState(sid,scene).size());
  jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,3)",sid);temporal.writeFacility(sid,b,scenes.findById(scene).orElseThrow(),"绳结","已松动","实际松动");
  assertFalse(facilityGoal("绳结","重新系紧"));assertTrue(facilityGoal("绳结","已松动"));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.state'))='已松动'",Integer.class,sid));
 }
 @Test void ambiguousSameTickFacilityCopiesAreReportedInsteadOfGuessing()throws Exception{
  legacyFacility(player,"conflict-one","绳结","已解开",2);legacyFacility(a,"conflict-two","绳结","系紧",2);
  assertThrows(IllegalStateException.class,()->temporal.sceneState(sid,scene));assertThrows(IllegalStateException.class,()->facilityGoal("绳结","已解开"));
 }
 @Test void facilityNamesAreComparedExactlyRatherThanByDatabaseCollation(){
  facilityEffect("Aa","","上层设施");facilityEffect("aa","","下层设施");facilityEffect("Aa","上层设施","仅上层改变");
  assertTrue(facilityGoal("aa","下层设施"));assertTrue(facilityGoal("Aa","仅上层改变"));assertEquals(2,temporal.sceneState(sid,scene).size());
 }
 @Test void facilityReferencesFitTheExistingSchemaAndSeparateScenes(){
  String remote="f".repeat(32);jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处','[]','绳结',1,0)",remote,sid);
  temporal.writeFacility(sid,player,scenes.findById(remote).orElseThrow(),"绳结","远处系紧","实际系紧");temporal.writeFacility(sid,player,scenes.findById(scene).orElseThrow(),"绳结","近处松开","实际松开");
  assertEquals("远处系紧",temporal.sceneState(sid,remote).get(0).get("state"));assertTrue(facilityGoal("绳结","近处松开"));
  assertEquals(76,jdbc.queryForObject("SELECT MAX(CHAR_LENGTH(source_ref)) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
 }
 @Test void facilityChangesFromBothPathsRemainInsideTheRestoredBranch(){
  facilityEffect("Aa","","已打开");temporal.writeFacility(sid,a,scenes.findById(scene).orElseThrow(),"绳结","松开","实际松开");
  assertEquals(2,temporal.sceneState(sid,scene).size());checkpoints.restore(sid,0);assertTrue(temporal.sceneState(sid,scene).isEmpty());assertFalse(facilityGoal("Aa","已打开"));
 }

 void bodyAwake(String id,boolean awake)throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','身体',?,'BODY_STATE','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,id,json.writeValueAsString(Map.of("awake",awake)),scene);
 }
 @Test void zeroAndNegativeGuiCanStillSpeakHearAndAppearAbleToAct(){
  jdbc.update("UPDATE god_profile SET base_gui=0 WHERE god_id=?",a);jdbc.update("UPDATE god_profile SET base_gui=-5 WHERE god_id=?",b);
  knowledge.hear(sid,a,b,"实际说出的话",scenes.findById(scene).orElseThrow(),1,1);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD'",Integer.class,sid,b));
  assertTrue(physical.physicalStates(sid,scenes.findById(scene).orElseThrow()).stream().filter(v->a.equals(v.get("id")) || b.equals(v.get("id"))).allMatch(v->Boolean.TRUE.equals(v.get("canAct"))));
 }
 @Test void unconsciousSpeakerOrListenerStillCannotProduceDeliveredSpeech()throws Exception{
  bodyAwake(a,false);knowledge.hear(sid,a,b,"昏迷者不能说",scenes.findById(scene).orElseThrow(),1,1);bodyAwake(a,true);bodyAwake(b,false);knowledge.hear(sid,a,b,"昏迷者听不到",scenes.findById(scene).orElseThrow(),2,1);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD'",Integer.class,sid));assertFalse(physical.awake(sid,b,scenes.findById(scene).orElseThrow()));
 }
 @Test void zeroGuiCanScheduleButUnconsciousActorCannot()throws Exception{
  String destination="causal_available_dest";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外',1,0)",destination,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,scene,destination);
  jdbc.update("UPDATE god_profile SET base_gui=0 WHERE god_id IN (?,?)",a,b);assertTrue(turns.schedule(sid,a,scene,new WorldTurnService.PlannedStep("LEAVE","",destination,1,"去门外查看")));bodyAwake(b,false);assertFalse(turns.schedule(sid,b,scene,new WorldTurnService.PlannedStep("LEAVE","",destination,1,"去门外查看")));
 }
 @Test void zeroGuiLegacyTurnStillCommitsRealActionAndHearing(){
  jdbc.update("UPDATE god_profile SET base_gui=0 WHERE session_id=?",sid);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);return messages.get(1).get("content").contains("姓名：工匠甲")?r(Map.of("behavior","RESPOND","target_god_id",b,"speech","我来检查绳结。","action_text","检查已有石柱后的绳结位置","decision_point","NONE")):r(Map.of("behavior","IGNORE"));}).when(model).chatSubForJson(anyList());
  var response=actions.processActionWithDb(new com.bs.narrative.dto.ActionRequest(pid,scene,"观察绳结",new com.bs.narrative.dto.ActionRequest.StructuredIntent("OBSERVE",player,"",null,null,1.0,"观察")),sid);
  assertTrue(response.factsChinese().stream().anyMatch(text->text.contains("检查已经结束")));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.recordType'))='UTTERANCE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.actorId'))=? AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.text'))=?",Integer.class,sid,b,a,"我来检查绳结。"));
  assertTrue(temporal.roleContext(sid,b).contains("我来检查绳结。"));
 }
 @Test void anotherActorsPortableItemCannotBeChangedAsAFacility(){
  jdbc.update("UPDATE god_profile SET inventory='[\"锯囊\"]' WHERE god_id=?",b);var location=scenes.findById(scene).orElseThrow();location.setSceneSummary(location.getSceneSummary()+"乙携带锯囊。");scenes.saveAndFlush(location);
  var packet=json.valueToTree(Map.of("process","不能绕过背包归属。","durationSeconds",1,"status","COMPLETED","effects",List.of(Map.of("kind","FACILITY","objectName","锯囊","before","","after","已被拆开"))));
  assertThrows(IllegalArgumentException.class,()->operationEffects.apply(sid,location,a,player,packet,new HashSet<>()));assertFalse(facilityGoal("锯囊","已被拆开"));assertEquals("[\"锯囊\"]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,b));
 }

}
