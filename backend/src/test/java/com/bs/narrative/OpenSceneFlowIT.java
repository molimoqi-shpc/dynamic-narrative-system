package com.bs.narrative;
import com.bs.narrative.service.*;import com.bs.narrative.domain.*;import com.bs.narrative.dto.*;import com.bs.narrative.llm.*;import com.bs.narrative.repository.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.mock.mockito.MockBean;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;
import java.util.*;import java.util.concurrent.atomic.AtomicInteger;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"}) @Transactional
class OpenSceneFlowIT {
 @Autowired GameActionService actions;@Autowired OpenSceneService open;@Autowired SessionStateService state;@Autowired JdbcTemplate jdbc;@Autowired GodProfileRepository gods;@Autowired GameSceneRepository scenes;
 @MockBean LLMProvider model;
 String sid="open_scene_it",pid="open_user_it",player="open_player_it",scene="open_altar_it",ritual="open_ritual_it",child="open_child_it";
 static LLMResponse r(String j){return new LLMResponse(j,j,0,0,0,0);}
 @BeforeEach void seed(){
  for(var id:List.of(player,ritual,child))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',10,10,10,10,20,75,'[]')",id,sid,id.equals(player)?"你":id.equals(ritual)?"厉沧海":"昏迷的孩童");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'祭坛',?,'厉沧海立于黑幡前准备仪式，石柱上缚着昏迷的孩童。你隐于暗处。',0,0)",scene,sid,"[\""+ritual+"\",\""+child+"\"]");
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'open_scene_test','unused',?,?)",pid,player,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,status,view_mode) VALUES(?,?,?,'PLAYING','DREAMER')",sid,pid,player);state.initialize(sid,pid);
 }
 String waitJson(){return "{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"EVENT\",\"eventText\":\"仪式开始\"}}";}
 void mainResponses(String resolution){when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String system=msgs.get(0).get("content");if(system.contains("现场行动裁定者"))return r(resolution);if(system.contains("意图解析器"))return r(waitJson());return r("{\"visible\":{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"现场的实际变化继续推进。\"}]}}");});}
 @Test void aThreatDoesNotSatisfyEventWaitAndFreeRitualActionIsPersisted(){
  String event="厉沧海转身向黑幡抬起双手，开始仪式，注意力暂时落在幡上。";
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\""+event+"\",\"view\":{\"awake\":true,\"bound\":false,\"playerKnown\":false,\"activity\":\"仪式已经开始，正在操作黑幡\",\"percepts\":[\"黑幡仍在眼前\"]}}],\"waitingSatisfied\":true,\"waitingBasis\":\"开始仪式\",\"opportunity\":\"他正在操作黑幡，是否靠近还需判断距离。\"}");
  AtomicInteger count=new AtomicInteger();List<String> childPrompts=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");if(prompt.contains("姓名：厉沧海")){
   if(count.incrementAndGet()==1)return r("{\"behavior\":\"RESPOND\",\"speech\":\"祭坛前不容立足。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"祭坛前不容立足\"}");
   return r("{\"behavior\":\"ACT\",\"action_text\":\"抬起双手操作黑幡，依自己的打算开始仪式。\"}");
  }childPrompts.add(prompt);return r("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+player+"\",\"speech\":\"躲在那边的人，替我松绑！\",\"decision_point\":\"PLAYER_CHOICE\",\"decision_basis\":\"替我松绑\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"隐匿自身，等到仪式开始，看看能否偷袭",null),sid);
  assertEquals(2,((Map<?,?>)response.ruleSnapshot().get("waiting")).get("phases"));assertEquals("READY",((Map<?,?>)response.ruleSnapshot().get("waiting")).get("status"));assertTrue(response.factsChinese().stream().anyMatch(f->f.contains("开始仪式")));
  assertTrue(response.lines().stream().noneMatch(l->l.speech().contains("替我松绑")));assertTrue(childPrompts.stream().noneMatch(p->p.contains(player) || p.contains("看看能否偷袭")));
  assertEquals("仪式已经开始，正在操作黑幡",open.view(sid,gods.findById(ritual).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  assertEquals(75,gods.findById(child).orElseThrow().getBaseGui());assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_ATTEMPT'",Integer.class,sid)>0);state.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_ATTEMPT'",Integer.class,sid));assertEquals("",open.view(sid,gods.findById(ritual).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
 }
 @Test void wakingIsAnExecutedTransitionAndDoesNotAutomaticallyRevealHiddenPlayer(){
  mainResponses("{\"events\":[{\"actorId\":\""+child+"\",\"text\":\"孩童被声响惊醒，睁开眼，身体仍被绳索缚住。\",\"view\":{\"awake\":true,\"bound\":true,\"playerKnown\":true,\"activity\":\"恢复意识\",\"percepts\":[\"身体被绳索缚住\"]},\"perceptionBasis\":\"\"}],\"waitingSatisfied\":false}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);if(msgs.get(1).get("content").contains("姓名：厉沧海"))return r("{\"behavior\":\"IGNORE\"}");return r("{\"behavior\":\"ACT\",\"action_text\":\"因身体不适尝试恢复意识。\",\"speech\":\"我已看见暗处那人，救我！\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"隐匿自身观察",new ActionRequest.StructuredIntent("OBSERVE",player,"",null,null,1.0,"观察")),sid);
  var view=open.view(sid,gods.findById(child).orElseThrow(),scenes.findById(scene).orElseThrow());assertEquals(true,view.get("awake"));assertEquals(true,view.get("bound"));assertEquals(false,view.get("playerKnown"));assertEquals("孩童",gods.findById(child).orElseThrow().getGodName());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_memory WHERE session_id=? AND god_id=? AND memory_type='PERSONAL' AND memory_content LIKE '【现场交涉】%'",Integer.class,sid,child));
  assertTrue(response.lines().stream().noneMatch(l->l.speech().contains("救我")));assertTrue(response.factsChinese().stream().anyMatch(f->f.contains("惊醒")));
 }
 @Test void unknownEventActorCannotWriteAnyRoleView(){
  mainResponses("{\"events\":[{\"actorId\":\"foreign_role\",\"text\":\"新的现场事实\",\"view\":{\"awake\":true}}]}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","操作黑幡");
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"仪式开始"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_ref LIKE 'view:%'",Integer.class,sid));
 }
 @Test void inventedWaitSatisfactionWithoutActualEventEvidenceIsRejected(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海原地观察黑幡，并未开始仪式。\",\"view\":{\"awake\":true,\"bound\":false}}],\"waitingSatisfied\":true,\"waitingBasis\":\"祭品已经献出\"}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","观察黑幡");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"仪式开始").waitReached());
 }

 @Test void smallRepeatedMovementIsNotACompletedWaitingOpportunity() throws Exception {
  mainResponses(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("events",List.of(Map.of("actorId",ritual,"text","厉沧海把手向黑幡挪了半寸，仍未开始事务。","view",Map.of("activity","继续准备"))),"progressed",false,"progressBasis","")));
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","抬手靠近黑幡");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"等待真实局势变化").progressed());
 }
 @Test void substantiveProgressRequiresEvidenceInExecutedEvent() throws Exception {
  mainResponses(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("events",List.of(Map.of("actorId",ritual,"text","厉沧海查看黑幡。","view",Map.of("activity","查看"))),"progressed",true,"progressBasis","仪式已经完成")));
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","查看黑幡");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"等待真实局势变化").progressed());
 }

 @Test void changeWaitContinuesPastMicroActionUntilARealOutcome() {
  AtomicInteger phase=new AtomicInteger();var json=new com.fasterxml.jackson.databind.ObjectMapper();
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);if(!msgs.get(1).get("content").contains("姓名：厉沧海"))return r(json.writeValueAsString(Map.of("behavior","IGNORE")));phase.incrementAndGet();return r(json.writeValueAsString(Map.of("behavior","ACT","action_text","操作黑幡直到完成检查或遇到明确阻碍。")));});
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String system=msgs.get(0).get("content");if(system.contains("意图解析器"))return r(json.writeValueAsString(Map.of("intent","WAIT","waiting",Map.of("condition","CHANGE"))));if(system.contains("现场行动裁定者")){boolean done=phase.get()>1;String text=done?"厉沧海完成黑幡检查，确认幡面已有裂口，原办法无法继续。":"厉沧海抬手挪动半寸，事务状态仍未改变。";return r(json.writeValueAsString(Map.of("events",List.of(Map.of("actorId",ritual,"text",text,"view",Map.of("activity",done?"检查结束，确认黑幡破损":"继续检查"))),"progressed",done,"progressBasis",done?"确认幡面已有裂口":"")));}return r(json.writeValueAsString(Map.of("visible",Map.of("lines",List.of(Map.of("godId","narrator","speech","检查得到实际结果，原办法已经受阻。"))))));});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"隐匿自身等待真实变化",null),sid);
  var waiting=(Map<?,?>)response.ruleSnapshot().get("waiting");assertEquals(2,waiting.get("phases"));assertEquals("READY",waiting.get("status"));assertEquals("检查结束，确认黑幡破损",open.view(sid,gods.findById(ritual).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
 }

 @Test void hiddenPlayerIsNotExposedThroughThirdPersonSceneDescription(){
  var current=scenes.findById(scene).orElseThrow();open.playerAction(sid,current,player,"OBSERVE","隐匿自身");String projected=open.project(sid,gods.findById(ritual).orElseThrow(),"厉沧海站在黑幡前。玩家在岸边隐匿观察。");assertTrue(projected.contains("厉沧海"));assertFalse(projected.contains("玩家"));
 }

 @Test void currentCastIsIntroducedOnceAndRewindRestoresIntroduction(){
  mainResponses("{}");when(model.chatSubForJson(anyList())).thenReturn(r("{\"behavior\":\"IGNORE\"}"));
  var req=new ActionRequest(pid,scene,"观察",new ActionRequest.StructuredIntent("OBSERVE",player,"",null,null,1.0,"观察"));
  var first=actions.processActionWithDb(req,sid);assertTrue(first.lines().get(0).speech().contains("此处在场的人物："));assertTrue(first.lines().get(0).speech().contains("厉沧海"));
  var second=actions.processActionWithDb(req,sid);assertFalse(second.lines().get(0).speech().contains("此处在场的人物："));state.restore(sid,0);var restored=actions.processActionWithDb(req,sid);assertTrue(restored.lines().get(0).speech().contains("此处在场的人物："));
 }

 String rescueResult(String status,boolean bound,String target){
  return "{\"events\":[{\"actorId\":\""+player+"\",\"text\":\"你解开了孩童手腕处的绳结，他仍昏迷，仍留在石柱旁。\",\"view\":{\"activity\":\"检查救援结果\"}}],\"rescueResult\":{\"targetId\":\""+target+"\",\"status\":\""+status+"\",\"basis\":\"解开了孩童手腕处的绳结\",\"view\":{\"bound\":"+bound+",\"position\":\"石柱旁\",\"activity\":\"手腕束缚已解除，仍昏迷\"}}}";
 }
 @Test void actualRescueUpdatesTargetRestraintWithoutForcingWakeOrDeparture(){
  mainResponses(rescueResult("COMPLETED",false,child));
  var result=open.resolveRescue(sid,scenes.findById(scene).orElseThrow(),player,List.of(),List.of(),"",child,"趁守卫转身，靠近孩童解开手腕绳结");
  var v=open.view(sid,gods.findById(child).orElseThrow(),scenes.findById(scene).orElseThrow());
  assertEquals(false,v.get("bound"));assertEquals(false,v.get("awake"));assertEquals("石柱旁",v.get("position"));assertTrue(result.events().get(0).contains("解开"));
  assertEquals(75,gods.findById(child).orElseThrow().getBaseGui());assertTrue(scenes.findById(scene).orElseThrow().getPresentGods().contains(child));
  state.restore(sid,0);assertEquals(true,open.view(sid,gods.findById(child).orElseThrow(),scenes.findById(scene).orElseThrow()).get("bound"));
 }
 @Test void blockedRescueCannotFreeTargetOrPartiallyPersistViews(){
  mainResponses(rescueResult("BLOCKED",false,child));
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->open.resolveRescue(sid,scenes.findById(scene).orElseThrow(),player,List.of(),List.of(),"",child,"尝试解开绳结"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_ref LIKE 'view:%'",Integer.class,sid));
 }
 @Test void rescueCannotModifyAnotherTarget(){
  mainResponses(rescueResult("COMPLETED",false,ritual));
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->open.resolveRescue(sid,scenes.findById(scene).orElseThrow(),player,List.of(),List.of(),"",child,"解开孩童绳结"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_ref LIKE 'view:%'",Integer.class,sid));
 }
 @Test void aBlockedRescueGivesAnActualObstacleAndKeepsRestraints(){
  mainResponses("{\"events\":[{\"actorId\":\""+player+"\",\"text\":\"你靠近石柱，但守卫封住去路，未能碰到绳结。\",\"view\":{\"activity\":\"被守卫挡住\"}}],\"rescueResult\":{\"targetId\":\""+child+"\",\"status\":\"BLOCKED\",\"basis\":\"守卫封住去路，未能碰到绳结\",\"view\":{\"bound\":true}}}");
  var result=open.resolveRescue(sid,scenes.findById(scene).orElseThrow(),player,List.of(),List.of(),"",child,"尝试解开绳结");
  assertTrue(result.events().get(0).contains("未能碰到"));assertEquals(true,open.view(sid,gods.findById(child).orElseThrow(),scenes.findById(scene).orElseThrow()).get("bound"));
 }
 @Test void playerRescueFlowsThroughGameActionAndPersistsThePhysicalOutcome(){
  mainResponses(rescueResult("COMPLETED",false,child));when(model.chatSubForJson(anyList())).thenReturn(r("{\"behavior\":\"IGNORE\"}"));
  var result=actions.processActionWithDb(new ActionRequest(pid,scene,"解开孩童手腕的绳结",new ActionRequest.StructuredIntent("RESCUE",player,child,null,null,1.0,"解开绳结")),sid);
  assertEquals(false,open.view(sid,gods.findById(child).orElseThrow(),scenes.findById(scene).orElseThrow()).get("bound"));
  assertTrue(result.factsChinese().stream().anyMatch(f->f.contains("解开了孩童")));assertEquals("平静",gods.findById(child).orElseThrow().getGodEmotion());
 }


 @Test void aNegatedQuotedEventCannotClaimWaitingSatisfied(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海并未开始仪式。\",\"view\":{\"activity\":\"未开始\"}}],\"waitingSatisfied\":true,\"waitingBasis\":\"开始仪式\"}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","观察黑幡");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"仪式开始").waitReached());
 }
 @Test void anAnnouncedFutureEventCannotClaimWaitingSatisfied(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海准备开始仪式。\",\"view\":{\"activity\":\"准备\"}}],\"waitingSatisfied\":true,\"waitingBasis\":\"开始仪式\"}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","准备仪式");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"仪式开始").waitReached());
 }
 @Test void aRealBlockerInterruptsEventWaitingAfterOnePhase(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海检查黑幡，发现幡骨折断，仪式无法继续，转而检查折口。\",\"view\":{\"activity\":\"发现折口，原仪式受阻\"}}],\"progressed\":true,\"progressBasis\":\"发现幡骨折断\",\"waitingSatisfied\":false,\"waitingInterrupted\":true,\"interruptionBasis\":\"幡骨折断，仪式无法继续\"}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return m.get(1).get("content").contains("姓名：厉沧海")?r("{\"behavior\":\"ACT\",\"action_text\":\"检查黑幡，按实际结果处理仪式。\"}"):r("{\"behavior\":\"IGNORE\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"隐匿自身，等待仪式开始",null),sid);
  var w=(Map<?,?>)response.ruleSnapshot().get("waiting");assertEquals("INTERRUPTED",w.get("status"));assertEquals(1,w.get("phases"));
  assertTrue(response.factsChinese().stream().anyMatch(f->f.contains("幡骨折断")));
 }
 @Test void anInventedInterruptionWithoutEventEvidenceCannotStopWaiting(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海看向黑幡。\",\"view\":{\"activity\":\"观察\"}}],\"waitingInterrupted\":true,\"interruptionBasis\":\"黑幡折断\"}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","观察黑幡");
  assertFalse(open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"仪式开始").interrupted());
 }


 @Test void aGroundedBlockerWithAnOpportunityStopsWaitEvenWhenModelOmitsInterruptFlag(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海检查后发现黑幡破损，停手退回石阶。\",\"view\":{\"activity\":\"检查结束，原方法受阻\"}}],\"progressed\":true,\"progressBasis\":\"发现黑幡破损\",\"waitingSatisfied\":false,\"opportunity\":\"可以查看折口或决定离开。\"}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return m.get(1).get("content").contains("姓名：厉沧海")?r("{\"behavior\":\"ACT\",\"action_text\":\"检查黑幡。\"}"):r("{\"behavior\":\"IGNORE\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"等待仪式开始",null),sid);var w=(Map<?,?>)response.ruleSnapshot().get("waiting");assertEquals("INTERRUPTED",w.get("status"));assertEquals(1,w.get("phases"));
 }
 @Test void completedEventIsNotMislabeledUnfinishedWhenSomeoneAlsoAsksPlayer(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海开始仪式，黑幡升起。\",\"view\":{\"activity\":\"仪式进行中\"}}],\"waitingSatisfied\":true,\"waitingBasis\":\"开始仪式\"}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return m.get(1).get("content").contains("姓名：厉沧海")?r("{\"behavior\":\"ACT\",\"action_text\":\"启动仪式。\",\"target_god_id\":\""+player+"\",\"speech\":\"你愿意帮忙吗？\",\"decision_point\":\"PLAYER_CHOICE\",\"decision_basis\":\"你愿意帮忙吗\"}"):r("{\"behavior\":\"IGNORE\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"等待仪式开始",null),sid);assertEquals("READY",((Map<?,?>)response.ruleSnapshot().get("waiting")).get("status"));
 }

 @Test void aPhantomToolGetsOneBoundedRepairWithoutRerunningNpcDecisions() {
  AtomicInteger calls=new AtomicInteger();
  when(model.chatForJson(anyList())).thenAnswer(call->{calls.incrementAndGet();if(calls.get()==2){List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().noneMatch(m->"assistant".equals(m.get("role"))),"unexecuted fabrications must not become assistant history");assertTrue(messages.get(2).get("content").contains("铁箍"));}return calls.get()==1
   ?r("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海取来铁箍修理幡骨。\"}]}")
   :r("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海检查折口，确认缺少固定工具，无法继续，退到幡旁。\",\"uses\":[],\"view\":{\"activity\":\"缺少工具，修理受阻\"}}],\"progressed\":true,\"progressBasis\":\"缺少固定工具\"}");});
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","尝试修理幡骨");
  var result=open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"");
  assertEquals(2,calls.get());assertTrue(result.events().get(0).contains("缺少固定工具"));
  assertTrue(gods.findById(ritual).orElseThrow().getInventory().isEmpty());verify(model,never()).chatSubForJson(anyList());
 }
 @Test void twoInvalidToolResponsesFailWithoutPartialPhysicalOrInventoryChanges() {
  when(model.chatForJson(anyList())).thenReturn(r("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海取来铁箍修理幡骨。\",\"view\":{\"activity\":\"已经修复\"}}]}"));
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","修理幡骨");
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),""));
  verify(model,times(2)).chatForJson(anyList());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_VIEW'",Integer.class,sid));
  assertTrue(gods.findById(ritual).orElseThrow().getInventory().isEmpty());
 }

 @Test void unconsciousListenerCannotRememberUnheardSpeechAfterWaking(){
  var notebook=new KnowledgeService(jdbc,gods);org.springframework.test.util.ReflectionTestUtils.setField(notebook,"openScenes",open);
  var s=scenes.findById(scene).orElseThrow();notebook.hear(sid,ritual,child,"仅在昏迷期间说出的暗号R271",s,1,1);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD'",Integer.class,sid,child));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'醒来视角','{\"awake\":true}','SCENE_VIEW','',?,'祭坛',2)",sid,child,"view:"+scene,scene);
  assertFalse(notebook.privateContext(sid,child).contains("R271"));
  notebook.hear(sid,ritual,child,"醒来后向你说明当前风险",s,2,2);
  assertTrue(notebook.privateContext(sid,child).contains("当前风险"));
 }

 @Test void actualFailedAttemptStopsWaitWithoutProgressOrOpportunityFlags(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海抬起幡骨试着启动，但裂口卡死，未能继续抬起，便退开。\",\"view\":{\"activity\":\"本次尝试受阻\"}}],\"progressed\":false,\"waitingSatisfied\":false}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return m.get(1).get("content").contains("姓名：厉沧海")?r("{\"behavior\":\"ACT\",\"action_text\":\"尝试启动现有黑幡\"}"):r("{\"behavior\":\"IGNORE\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,"等到仪式实际开始",null),sid);var w=(Map<?,?>)response.ruleSnapshot().get("waiting");assertEquals("INTERRUPTED",w.get("status"));assertEquals(1,w.get("phases"));
 }

 @Test void selectedConversationDeliversTheExactUnquotedWordsWithoutAnIntentGuess(){
  String words="厉沧海，你为什么留在此处？";
  List<String> prompts=Collections.synchronizedList(new ArrayList<>());
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);assertFalse(msgs.get(0).get("content").contains("意图解析器"),"explicit recipient must not be guessed again");return r("{\"visible\":{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"你向厉沧海发问。\"}]}}");});
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");prompts.add(prompt);if(prompt.contains("姓名：厉沧海"))return r("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+player+"\",\"speech\":\"我还要处理黑幡，你别挡路。\"}");return r("{\"behavior\":\"IGNORE\"}");});
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,words,null,new ActionRequest.DialogueInput(ritual,words)),sid);
  assertEquals(words,response.ruleSnapshot().get("spokenText"));assertEquals("SPOKEN",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
  assertTrue(prompts.stream().anyMatch(p->p.contains("姓名：厉沧海") && p.contains("【玩家确实向你说出的话】"+words)));
  assertTrue(prompts.stream().filter(p->p.contains("姓名：昏迷的孩童")).noneMatch(p->p.contains(words)));
  assertTrue(response.lines().stream().anyMatch(l->l.speech().contains("我还要处理黑幡，你别挡路。")));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD' AND source_god_id=? AND content=?",Integer.class,sid,ritual,player,words));
 }
 @Test void choosingSilenceIsVisibleAndDoesNotForgeASpokenReply(){
  mainResponses("{}");when(model.chatSubForJson(anyList())).thenReturn(r("{\"behavior\":\"IGNORE\"}"));
  String words="我能和你谈一谈吗？";
  var response=actions.processActionWithDb(new ActionRequest(pid,scene,words,null,new ActionRequest.DialogueInput(ritual,words)),sid);
  assertEquals("NO_REPLY",((Map<?,?>)response.ruleSnapshot().get("dialogue")).get("status"));
  assertTrue(response.factsChinese().stream().anyMatch(t->t.contains("厉沧海这次没有口头回应")));
  assertTrue(response.npcDecisions().stream().allMatch(d->d.speech().isBlank()));
 }
 @Test void explicitConversationCannotPointOutsideTheScene(){
  var ex=assertThrows(com.bs.narrative.exception.BusinessException.class,()->actions.processActionWithDb(new ActionRequest(pid,scene,"你是谁？",null,new ActionRequest.DialogueInput("other_save_role","你是谁？")),sid));
  assertTrue(ex.getMessage().contains("不在此处"));verifyNoInteractions(model);
 }
 @Test void conversationCannotSmuggleDifferentUnspokenText(){
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->actions.processActionWithDb(new ActionRequest(pid,scene,"私下寻找出口",null,new ActionRequest.DialogueInput(ritual,"你是谁？")),sid));verifyNoInteractions(model);
 }
 @Test void ordinaryCooperationExecutesItsActionWithoutCreatingACombatProtector(){
  mainResponses("{\"events\":[{\"actorId\":\""+ritual+"\",\"text\":\"厉沧海用双手检查黑幡，检查已经结束。\",\"view\":{\"activity\":\"黑幡检查完成\"}}]}");
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return m.get(1).get("content").contains("姓名：厉沧海")?r("{\"behavior\":\"SUPPORT\",\"target_god_id\":\""+child+"\",\"speech\":\"我来帮着检查。\",\"action_text\":\"用双手检查现有黑幡。\"}"):r("{\"behavior\":\"IGNORE\"}");});
  String words="请一起检查现场。";var response=actions.processActionWithDb(new ActionRequest(pid,scene,words,null,new ActionRequest.DialogueInput(ritual,words)),sid);
  assertTrue(response.factsChinese().stream().anyMatch(f->f.contains("检查已经结束")));
  assertFalse(response.factsChinese().stream().anyMatch(f->f.contains("挡下攻击") || f.contains("承受了这次攻击")));
 }

 @Test void typedBodyActionAndFacilityStateAreCommittedTogetherAndRewindTogether()throws Exception{
  var target=scenes.findById(scene).orElseThrow();target.setSceneSummary("厉沧海站在木闸旁，木闸仍关闭。孩童昏迷且被缚在旁边。");scenes.saveAndFlush(target);
  String text="厉沧海用掌缘比量闸板的边缘，完成检查，闸板仍关闭。";var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var response=json.valueToTree(Map.of("executionVersion",2,"sceneChanges",List.of(Map.of("objectName","木闸","state","仍关闭","basis",text)),"events",List.of(Map.of("actorId",ritual,"text",text,"uses",List.of(),"nonItemUses",List.of(Map.of("kind","BODY","name","掌缘","basis",text),Map.of("kind","SCENE","name","木闸","basis",text)),"execution",Map.of("task","检查木闸边缘","status","COMPLETED","basis",text,"stopReason",""),"view",Map.of("activity","木闸检查已经完成")))));
  mainResponses(response.toString());state.begin(sid,pid);jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,1)",sid);
  var attempted=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","用掌缘比量木闸边缘，完成检查");
  var resolution=open.resolveRescue(sid,target,player,List.of(attempted),List.of(),"等待实际检查结果","","");
  assertTrue(resolution.progressed());var view=open.view(sid,gods.findById(ritual).orElseThrow(),target);assertEquals("COMPLETED",((Map<?,?>)view.get("execution")).get("status"));
  assertTrue(open.context(sid,gods.findById(ritual).orElseThrow()).contains("用掌缘比量木闸边缘，完成检查"));
  assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",String.class,sid).contains("仍关闭"));
  state.complete(sid,"检查已完成");state.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
  assertTrue(((Map<?,?>)open.view(sid,gods.findById(ritual).orElseThrow(),scenes.findById(scene).orElseThrow()).get("execution")).isEmpty());
 }
 @Test void oneRepairReceivesAllResourceAndSpeechErrorsAndSavesOnlyTheActualResult(){
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  String text="厉沧海用掌缘检查幡杆，检查已经结束，黑幡仍在原位。";
  var valid=json.valueToTree(Map.of("executionVersion",2,"sceneChanges",List.of(),"events",List.of(Map.of("actorId",ritual,"text",text,"uses",List.of(),"nonItemUses",List.of(Map.of("kind","BODY","name","掌缘"),Map.of("kind","SCENE","name","黑幡")),"execution",Map.of("task","检查黑幡","status","COMPLETED","stopReason",""),"view",Map.of("activity","黑幡检查已经完成")))));
  var invalid=valid.deepCopy();var event=(com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("events").get(0);
  event.put("text","厉沧海检查黑幡，随后对孩童说情况稳定，黑幡仍在原位。");
  event.withArray("nonItemUses").addObject().put("kind","SCENE").put("name","幡旁地面");
  event.withArray("nonItemUses").addObject().put("kind","SCENE").put("name","幡座");
  event.withArray("uses").addObject().put("ref","item_missing");
  AtomicInteger calls=new AtomicInteger();when(model.chatForJson(anyList())).thenAnswer(call->{
   if(calls.incrementAndGet()==1)return r(invalid.toString());
   List<Map<String,String>> messages=call.getArgument(0);String correction=messages.get(messages.size()-1).get("content");
   assertFalse(correction.contains("indirect speech"));assertTrue(correction.contains("幡旁地面"));assertTrue(correction.contains("幡座"));assertTrue(correction.contains("黑幡"));assertTrue(correction.contains("item_missing"));return r(valid.toString());
  });
  var attempted=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","检查黑幡");
  var resolution=open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(attempted),List.of(),"等待检查结果");
  assertEquals(2,calls.get());assertEquals(List.of(text),resolution.events());
  assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",String.class,sid).contains(text));
 }
 @Test void eightCompactActionsWithAuxiliaryBodyLabelsSaveWithoutARepair(){
  String[] names={"云崖子","赤霄真人","凌虚剑君","岳青霜","新晋弟子们","黑面散人","苏婉","周元"};
  var ids=new ArrayList<String>();var attempts=new ArrayList<NpcAgentService.NpcDecision>();var json=new com.fasterxml.jackson.databind.ObjectMapper();var result=json.createObjectNode().put("executionVersion",2);result.putArray("sceneChanges");var events=result.putArray("events");
  for(int i=0;i<names.length;i++){
   String id="compact_it_"+i;ids.add(id);jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',75,'[]')",id,sid,names[i]);
   attempts.add(new NpcAgentService.NpcDecision(id,"ACT","","",false,false,"","","",null,false,null,"","NONE","","观察场内动静，然后站在当前位置"));
   var event=events.addObject().put("actorIndex",i).put("text",names[i]+"侧身调整站位，以眼角余光观察场内动静，随后站定。");event.putArray("uses");event.putArray("nonItemUses").addObject().put("kind","BODY").put("name","眼角");event.putObject("execution").put("status","COMPLETED");
  }
  var target=scenes.findById(scene).orElseThrow();target.setPresentGods(ids);target.setSceneSummary("问道坪上，"+String.join("、",names)+"各自在场。登仙台尚未开启。");scenes.saveAndFlush(target);
  mainResponses(result.toString());var resolution=open.resolve(sid,target,player,attempts,List.of(),"继续等待掌门指示");
  assertEquals(8,resolution.events().size());verify(model,times(1)).chatForJson(anyList());
  for(String id:ids)assertEquals("COMPLETED",((Map<?,?>)open.view(sid,gods.findById(id).orElseThrow(),target).get("execution")).get("status"));
 }
 @Test void anUnreceivedPlayerItemCannotBeRelabeledAsASceneObject(){
  var owner=gods.findById(player).orElseThrow();owner.setInventory(new ArrayList<>(List.of("铜针")));gods.saveAndFlush(owner);
  var target=scenes.findById(scene).orElseThrow();target.setSceneSummary("厉沧海站在黑幡前。你持有铜针，尚未交给任何人。");scenes.saveAndFlush(target);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();String text="厉沧海用铜针探查黑幡，检查已经结束。";
  var result=json.valueToTree(Map.of("executionVersion",2,"sceneChanges",List.of(),"events",List.of(Map.of("actorId",ritual,"text",text,"uses",List.of(),"nonItemUses",List.of(Map.of("kind","SCENE","name","铜针")),"execution",Map.of("task","用铜针检查","status","COMPLETED","stopReason","")))));
  when(model.chatForJson(anyList())).thenReturn(r(result.toString()));
  var attempt=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","用铜针检查黑幡");
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->open.resolve(sid,target,player,List.of(attempt),List.of(),""));
  assertTrue(gods.findById(ritual).orElseThrow().getInventory().isEmpty());assertEquals(List.of("铜针"),gods.findById(player).orElseThrow().getInventory());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
 }
 @Test void waitingForSpeechUsesOnlyActualCurrentUtterancesAndCannotCompleteAPhysicalEvent(){
  String words="各自守好本位，不得近台。";
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var draft=json.valueToTree(Map.of("executionVersion",2,"sceneChanges",List.of(),"waitingSatisfied",true,"waitingSource","SPEECH","waitingBasis",words,"events",List.of(Map.of("actorIndex",0,"text","厉沧海站定原位，抬手指向既有黑幡，随后放下手臂。","uses",List.of(),"execution",Map.of("status","COMPLETED")))));
  when(model.chatForJson(anyList())).thenReturn(r(draft.toString()));
  var d=new NpcAgentService.NpcDecision(ritual,"ACT",words,"",false,false,"","","",null,false,null,"","NONE","","站定原位，指向黑幡");
  var target=scenes.findById(scene).orElseThrow();
  assertTrue(open.resolve(sid,target,player,List.of(d),List.of(),"等待他的指示").waitReached());
  assertFalse(open.resolve(sid,target,player,List.of(d),List.of(),"等待仪式实际开始").waitReached());
  var silent=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","站定原位，指向黑幡");
  assertFalse(open.resolve(sid,target,player,List.of(silent),List.of(),"等待他的指示").waitReached(),"an old or fabricated quote cannot complete this wait");
 }
 @Test void versionedActionCanHearSomeoneSpeakingWithoutLosingItsMovement(){
  String text="厉沧海听到同行人开口问话，顺势上前半步，停在黑幡旁观察。";
  mainResponses("{\"executionVersion\":2,\"sceneChanges\":[],\"events\":[{\"actorIndex\":0,\"text\":\""+text+"\",\"uses\":[],\"execution\":{\"status\":\"COMPLETED\"}}]}");
  var d=new NpcAgentService.NpcDecision(ritual,"ACT","","",false,false,"","","",null,false,null,"","NONE","","听到问话后上前半步观察黑幡");
  assertEquals(List.of(text),open.resolve(sid,scenes.findById(scene).orElseThrow(),player,List.of(d),List.of(),"").events());
  verify(model,times(1)).chatForJson(anyList());
 }
 @Test void dedicatedWaitCheckRequiresCurrentExactEvidenceAndRejectsAPromisedPhysicalOutcome(){
  var target=scenes.findById(scene).orElseThrow();String speech="各自守好本位，不得近台。";
  var d=new NpcAgentService.NpcDecision(ritual,"RESPOND",speech,"",false,false,"","","",null,false,null,"","NONE","","");
  when(model.chatForJson(anyList())).thenReturn(r("{\"reached\":true,\"source\":\"speech_0\",\"basis\":\"不得近台\"}"));
  assertTrue(open.currentWaitReached(target,"等待厉沧海指示",List.of(d),List.of()));
  assertFalse(open.currentWaitReached(target,"等待仪式开始",List.of(d),List.of()));
  assertFalse(open.currentWaitReached(target,"等待厉沧海指示",List.of(),List.of()));
  when(model.chatForJson(anyList())).thenReturn(r("{\"reached\":true,\"source\":\"speech_0\",\"basis\":\"旧的回答\"}"));
  assertFalse(open.currentWaitReached(target,"等待厉沧海指示",List.of(d),List.of()));
 }
}
