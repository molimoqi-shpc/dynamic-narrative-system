package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real committed stages and rewind in MySQL; model calls are mocked, with no enclosing test transaction. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class ConversationOperationIT {
 @Test void plannerReceivesPriorPublicChapterRatherThanPreviousRawPrivateInput()throws Exception{
  schedule(a,3600);schedule(b,3600);String input="继续询问石柱纹路。",chapter="工匠甲已解释过山字头，此刻正在展示另外一截纹路。";
  jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,0,'只在心里想的秘密','TALK','NO_OP',?,'{\"eventTimeline\":[{\"kind\":\"UTTERANCE\"}]}')",sid,pid,scene,chapter);
  for(int round=1;round<=3;round++)jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,?,'等待','TALK','NO_OP','你等候的这段时间已过去。','{}')",sid,pid,scene,round);
  var plans=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"routineConversation",true))));var context=json.readTree(m.get(1).get("content"));plans.incrementAndGet();assertEquals(chapter,context.path("previousChapters").get(0).path("text").asText());assertFalse(m.get(1).get("content").contains("只在心里想的秘密"));return response(Map.of("kind","DONE"));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(storyRequest("chapter-continuity",input)).get("status"));assertEquals(1,plans.get());verify(model,never()).chatSubForJson(anyList());
 }
 @Test void plannerInputRedactsOnlyPrivateSpansAndPreservesMatchingPublicWords()throws Exception{
  String privatePart="我只在心里想秘密暗号，不说出口。",publicPart="我说秘密暗号。";var p=json.createObjectNode().put("playerInput",privatePart+publicPart);var steps=p.putObject("authorization").putArray("steps");steps.addObject().put("type","PRIVATE_INTENT").put("text",privatePart);steps.addObject().put("type","SPEECH").put("text",publicPart);
  var method=ConversationOperationService.class.getDeclaredMethod("storyPlayerInput",com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);String input=(String)method.invoke(operations,p);assertFalse(input.contains(privatePart));assertTrue(input.endsWith(publicPart));assertEquals(1,input.split("秘密暗号",-1).length-1);
 }
 @Test void malformedIndirectSpeechAuthorizationRepairsOnceBeforeAnyAction()throws Exception{
  schedule(a,3600);schedule(b,3600);String input="我向工匠甲解释，我只想了解石柱纹路，不要求他交出工具。";var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);int n=calls.getAndIncrement();
   if(n==0)return response(Map.of("steps",List.of(Map.of("type","SPEECH","text","我只想了解石柱纹路，不要求他交出工具。","contextText","我向工匠甲解释","targetId",a))));
   if(n==1){assertTrue(m.get(m.size()-1).get("content").contains("contextText若提供，必须包含text"));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"routineConversation",true))));}
   assertEquals(input,json.readTree(m.get(1).get("content")).path("originalPlayerInput").asText());return response(Map.of("kind","DONE"));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("repair-indirect-intent",input));assertEquals("COMPLETED",done.get("status"));assertEquals(3,calls.get());assertEquals(2,json.readTree(payload("repair-indirect-intent")).path("authorizationAttempts").size());assertEquals("",story(done));verify(model,never()).chatSubForJson(anyList());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"pending","new","missing","false-with-effects","ongoing"})
 void alreadyFulfilledMovementCanCloseOnlyAnExplicitUnchangedPendingResult(String mode)throws Exception{
  var p=json.createObjectNode().put("playerRole",player);String job=a+(mode.equals("new")?"|reply_0":"|pending_0");p.putObject("decisions").putObject(job).put("localMovement",true).put("action_text","回松树取短锄");
  var result=json.valueToTree(result("早已回到松下，短锄在手，本次并未移动。",0,List.of()));
  if(!mode.equals("missing"))((com.fasterxml.jackson.databind.node.ObjectNode)result).put("movementOccurred",false);
  if(mode.equals("false-with-effects"))((com.fasterxml.jackson.databind.node.ObjectNode)result).withArray("effects").addObject().put("kind","ITEM_POSITION");
  if(mode.equals("ongoing"))((com.fasterxml.jackson.databind.node.ObjectNode)result).put("status","ONGOING");
  var method=ConversationOperationService.class.getDeclaredMethod("validateWorkProgress",com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,JsonNode.class);method.setAccessible(true);
  if(mode.equals("pending"))assertDoesNotThrow(()->method.invoke(operations,p,job,result));else assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(operations,p,job,result));
 }
 @Test void storyPlannerReceivesOriginalSilenceRestrictionsAlongsideParsedSteps()throws Exception{
  schedule(a,3600);schedule(b,3600);String input="我只在原地等三秒，不说话，也不发起新话题。";var planner=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text",input,"seconds",3))));
   var context=json.readTree(m.get(1).get("content"));planner.incrementAndGet();assertEquals(input,context.path("originalPlayerInput").asText());assertTrue(m.get(0).get("content").contains("摘要不能替代或放宽原话"));return response(Map.of("kind","DONE"));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("keep-silence-context",input));assertEquals("COMPLETED",done.get("status"));assertEquals(1,planner.get());assertFalse(story(done).contains("你说"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void importedSameActorPendingWorkIsSerialAndUsesCurrentFactsInsteadOfOldSnapshots()throws Exception{
  schedule(a,3600);schedule(b,3600);jdbc.update("UPDATE game_runtime SET story_seconds=10 WHERE session_id=?",sid);
  for(int i=0;i<2;i++){
   var d=choice("已选意愿","","",i==0?"先整理衣领":"再整理袖口");
   var frozen=List.of(Map.of("role","system","content",OperationEffectService.CONTRACT),Map.of("role","user","content",json.writeValueAsString(Map.of("actorId",a,"snapshotAtSeconds",0,"attempt","过时输入"))));
   var record=Map.of("decisions",d,"jobTimes",Map.of("start",10,"executionStart",10,"sceneId",scene),"executionContexts",frozen,"executions",result("过时的未提交故事",2,List.of()),"spokenJobs",true,"executedJobs",false);
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧待办',?,'ROLE_PENDING','',?,'',0)",sid,a,"old-pending:"+i,json.writeValueAsString(record),scene);
  }
  var calls=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待五秒","seconds",5))));
   var facts=json.readTree(m.get(1).get("content"));int index=calls.getAndIncrement();assertEquals(index==0?"先整理衣领":"再整理袖口",facts.path("attempt").asText());assertEquals(10+index,facts.path("snapshotAtSeconds").asLong());assertTrue(facts.has("pendingResumeContract"));
   String before=index==0?"":"衣领已整理",after=index==0?"衣领已整理":"袖口已整理";assertEquals(before,facts.path("resources").path(a).path("body").path("activity").asText());
   return response(result(after,1,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("activity",before),"after",Map.of("activity",after)))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("pending-serial-current","等待五秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(15L,done.get("storySeconds"));assertEquals(2,calls.get());assertFalse(story(done).contains("过时的未提交故事"));assertTrue(story(done).indexOf("衣领已整理")<story(done).indexOf("袖口已整理"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void pendingSerializationPreservesExplicitParallelAndDoesNotDropAnyDecision()throws Exception{
  var p=json.createObjectNode();var queue=p.putArray("queue");p.putArray("processed");p.putArray("executedJobs");var decisions=p.putObject("decisions");
  String first=a+"|pending_0",later=a+"|pending_1",parallel=a+"|pending_2",other=b+"|pending_3";
  for(String job:List.of(first,later,parallel,other)){queue.add(job);decisions.set(job,json.valueToTree(choice("原选择","","","动作")));}
  ((com.fasterxml.jackson.databind.node.ObjectNode)decisions.path(parallel)).putObject("work_update").put("mode","PARALLEL");var original=p.deepCopy();var jobs=new ArrayList<>(List.of(first,later,parallel,other));
  var method=ConversationOperationService.class.getDeclaredMethod("serializeAcceptedBodyWork",com.fasterxml.jackson.databind.node.ObjectNode.class,List.class);method.setAccessible(true);method.invoke(operations,p,jobs);
  assertEquals(List.of(first,parallel,other),jobs);assertEquals(original,p);
 }
 @Autowired ConversationOperationService operations;@Autowired SessionStateService checkpoints;@Autowired JdbcTemplate jdbc;@Autowired GameActionService game;
 @Autowired GodProfileRepository actors;@Autowired GameSceneRepository scenes;@Autowired OpenSceneService physical;
 @Autowired ObjectiveRuleService objectiveRules;@Autowired PlayerObjectiveService objectives;
 @Autowired GameActionService oldActions;@Autowired GameTranscriptService transcript;
 @Autowired WorldBookService worldBook;@Autowired OperationEffectService effects;
 @Autowired KnowledgeService knowledge;
 @Autowired KnownStateService knownState;@Autowired ItemPlacementService placements;@Autowired ItemStateService itemState;
 @Autowired OperationUsageService usage;
 @MockBean LLMProvider model;
 @Autowired com.bs.narrative.config.OperationSchema operationSchema;
 final ObjectMapper json=new ObjectMapper();String sid,pid,player,a,b,scene;
 @Test void capturedPublicUtteranceReachesBothIndependentRolesWithoutExtraActionOrInventedTarget()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/public-group-authorization.json"));String text=supplied.path("rawText").asText();var roles=new CopyOnWriteArrayList<String>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();for(var mail:own.path("pendingStimuli")){var heard=json.readTree(mail.path("content").asText());if(player.equals(heard.path("actorId").asText()) && heard.path("targetId").asText().isBlank())roles.add(role);}return response(choice("自己的判断","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);assertTrue(m.get(0).get("content").contains("玩家输入授权解析"));return response(supplied);}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("public-all",text)).get("status"));var saved=json.readTree(payload("public-all"));var speech=saved.path("events").get(0);assertEquals("UTTERANCE",speech.path("kind").asText());assertEquals(supplied.path("units").get(0).path("text"),speech.path("speech"));assertEquals(1,saved.path("events").size());
  for(String role:List.of(a,b))assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.recordType'))='UTTERANCE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.actorId'))=?",Integer.class,sid,role,player));assertTrue(roles.containsAll(List.of(a,b)));verify(model,times(1)).chatForJson(anyList());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"asleep","elsewhere"})
 void publicPlayerSpeechTriggersOnlyActualListeners(String unavailable)throws Exception{
  schedule(a,3600);schedule(b,3600);
  if(unavailable.equals("asleep"))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','身体','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,b,scene);
  else{var here=scenes.findById(scene).orElseThrow();here.setPresentGods(List.of(a));scenes.saveAndFlush(here);jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处',?,'在另一地点。',1,0)",scene+"_far",sid,jsonArray(b));}
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/public-group-authorization.json"));var heardBy=new CopyOnWriteArrayList<String>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));assertFalse(own.path("pendingStimuli").isEmpty());heardBy.add(own.path("id").asText());return response(choice("自己决定不回答","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->response(supplied)).when(model).chatForJson(anyList());assertEquals("COMPLETED",operations.create(nextInput("public-hearing-"+unavailable,supplied.path("rawText").asText())).get("status"));assertEquals(List.of(a),heardBy);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_INBOX','ROLE_CONSUMED')",Integer.class,sid,b));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"normal","malformed","stale"})
 void independentRoleContextsFeedOneMainSceneRequestAndRetainHealthyResults(String mode)throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(operations,"worldCohortBatching",true);
  try{
   for(String role:List.of(a,b))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'private-self','自身背景',?,'SELF_PROFILE','',?,'',0)",sid,role,"仅"+role+"知道的私事",scene);
   var choices=new AtomicInteger();var worlds=new AtomicInteger();var repaired=new java.util.concurrent.atomic.AtomicBoolean(false);var sizes=new CopyOnWriteArrayList<Integer>();
   doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();String other=role.equals(a)?b:a;assertTrue(m.get(1).get("content").contains("仅"+role+"知道的私事"));assertFalse(m.get(1).get("content").contains("仅"+other+"知道的私事"));choices.incrementAndGet();return response(choice(role+"的自主打算","","",role+"整理自己的衣物"));}).when(model).chatSubForJson(anyList());
   doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待8秒","seconds",8))));
    assertTrue(m.get(0).get("content").contains("主API统一现场推进"));var packet=json.readTree(m.get(1).get("content"));sizes.add(packet.path("attempts").size());int number=worlds.incrementAndGet();var output=json.createObjectNode();var results=output.putArray("results");
    for(var attempt:packet.path("attempts")){var facts=((com.fasterxml.jackson.databind.node.ObjectNode)packet.path("sharedFacts")).deepCopy();facts.setAll((com.fasterxml.jackson.databind.node.ObjectNode)attempt.path("facts"));String role=facts.path("actorId").asText();assertEquals(role+"的自主打算",facts.path("acceptedRoleChoice").path("inner_thought").asText());assertFalse(facts.has("serverEffectBaselines"));var after=new LinkedHashMap<String,Object>(Map.of("activity",role+"衣物已理好"));if(mode.equals("malformed") && !repaired.get() && role.equals(a))after.put("health",200);
     var item=results.insertObject(0).put("jobId",attempt.path("jobId").asText());item.set("result",json.valueToTree(result(role+"实际理好衣物。",4,List.of(Map.of("kind","BODY","actorId",role,"after",after)))));
    }
    if(mode.equals("stale") && number==1){var actor=actors.findById(a).orElseThrow();var here=scenes.findById(scene).orElseThrow();var body=new LinkedHashMap<>(physical.view(sid,actor,here));body.put("activity","冻结后真实发生的其他变化");jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','身体',?,'BODY_STATE','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,a,json.writeValueAsString(body),scene);}
    return response(output);
   }).when(model).chatForJson(anyList());
   String id="main-cohort-"+mode;var done=operations.create(nextInput(id,"等待8秒"));
   if(mode.equals("malformed")){assertEquals("PARTIAL",done.get("status"));var saved=json.readTree(payload(id));assertEquals(1,saved.path("executions").size(),"healthy member is retained");repaired.set(true);done=operations.recover(sid,pid,id,0);}
   assertEquals("COMPLETED",done.get("status"));assertEquals(2,choices.get());assertEquals(mode.equals("normal")?List.of(2):List.of(2,1),sizes);assertEquals(mode.equals("normal")?1:2,worlds.get());
   var saved=json.readTree(payload(id));assertEquals(2,saved.path("events").findValuesAsText("kind").stream().filter("ACTION_RESULT"::equals).count());assertEquals(worlds.get(),saved.path("worldCohortBatches").size());
   for(String role:List.of(a,b))assertEquals(role+"衣物已理好",physical.view(sid,actors.findById(role).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  }finally{org.springframework.test.util.ReflectionTestUtils.setField(operations,"worldCohortBatching",false);}
 }
 AtomicInteger isolationChoices()throws Exception{
  var choices=new AtomicInteger();doAnswer(call->{choices.incrementAndGet();return response(choice("自己的原选择","","","整理自己的衣物"));}).when(model).chatSubForJson(anyList());return choices;
 }
 @Test void longChapterCheckpointsRetainFullUtf8ProseAcrossUpdatesAndRepeatedSchemaInitialization()throws Exception{
  var p=json.createObjectNode().put("operationId","long-chapter").put("sceneId",scene).put("playerInput","继续观察").put("round",1).put("startSeconds",0).put("endSeconds",24);
  var events=p.putArray("events");for(int n=0;n<6;n++)events.addObject().put("kind","ACTION_RESULT").put("actorId",player).put("visibility","PUBLIC").put("text","第"+n+"段："+"实际看见的细节".repeat(550)).put("startSeconds",n*4).put("endSeconds",n*4+4);
  String prose=java.util.stream.StreamSupport.stream(events.spliterator(),false).map(e->e.path("text").asText()).collect(java.util.stream.Collectors.joining("\n\n"));assertTrue(prose.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535);
  var checkpoint=ConversationOperationService.class.getDeclaredMethod("checkpointChapter",String.class,String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,String.class);checkpoint.setAccessible(true);
  checkpoint.invoke(operations,sid,pid,p,"RUNNING");long log=p.path("logId").asLong();assertTrue(log>0);
  operationSchema.initialize();operationSchema.initialize();
  p.put("story",prose+"\n\n这一段自然结束。");checkpoint.invoke(operations,sid,pid,p,"COMPLETED");
  assertEquals("narrator："+p.path("story").asText(),jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=? AND log_id=?",String.class,sid,log));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND'",Integer.class,sid));verifyNoInteractions(model);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"旧的现场上下文", "\uD83D\uDCD6"})
 void largeUtf8PendingWorkSurvivesCancellationAndTheNextRoundWithoutReselecting(String unit)throws Exception{
  var choices=isolationChoices();
  doThrow(new LLMApiException("temporary world outage",false)).when(model).chatForJson(anyList());
  String id="large-pending";assertEquals("PARTIAL",operations.create(talk(id)).get("status"));assertEquals(2,choices.get());
  var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload(id));
  String large=unit.repeat(24000);
  var original=new LinkedHashMap<String,JsonNode>();
  for(var q:saved.path("queue")){String job=q.asText();if(job.startsWith(a+"|") || job.startsWith(b+"|")){
   var context=(com.fasterxml.jackson.databind.node.ArrayNode)saved.path("contexts").path(job);context.addObject().put("role","user").put("content",large);
   original.put(job,context.deepCopy());
  }}
  assertEquals(2,original.size());jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id=?",saved.toString(),sid,id);
  assertEquals("CANCELLED",operations.cancel(sid,pid,id,0).get("status"));
  var pending=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",String.class,sid);assertEquals(2,pending.size());
  for(String raw:pending){assertTrue(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535);var work=json.readTree(raw);String job=work.path("originDecisionId").asText().substring((id+":").length());assertEquals(original.get(job),work.path("contexts"));assertEquals("自己的原选择",work.path("decisions").path("inner_thought").asText());}
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待8秒","seconds",8))));return response(result("依照原决定整理好衣物。",4,List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("after-large-pending","等待8秒")).get("status"));assertEquals(2,choices.get(),"no accepted NPC decision is redrawn");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
  assertEquals(2,json.readTree(payload("after-large-pending")).path("events").findValuesAsText("text").stream().filter("依照原决定整理好衣物。"::equals).count());
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"recover,visibility","cancel,visibility","cancel-twice,visibility","recover,api","cancel,api","cancel-twice,api","recover,effects","cancel,effects","cancel-twice,effects"})
 void batchFailuresRecoverTogetherWithoutSerialPoisonedJobs(String path,String fault)throws Exception{
  var choices=isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();var repaired=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待8秒","seconds",8))));
   String role=json.readTree(m.get(1).get("content")).path("actorId").asText();calls.computeIfAbsent(role,k->new AtomicInteger()).incrementAndGet();
   if(!repaired.get() && fault.equals("api") && role.equals(a))throw new LLMApiException("batch unavailable",false);
   var packet=new LinkedHashMap<>(result(role.equals(a)?"甲完成原整理。":"乙完成原整理。",4,List.of()));if(!repaired.get()){if(fault.equals("effects"))packet.put("effects",List.of(Map.of("kind","UNSUPPORTED_TAIL")));else packet.put("visibility","INVALID");}return response(packet);
  }).when(model).chatForJson(anyList());
  String id="batch-failures-"+path+fault;assertEquals("PARTIAL",operations.create(talk(id)).get("status"));assertEquals(2,choices.get());
  if(path.equals("cancel-twice")){
   operations.cancel(sid,pid,id,0);id+="-again";assertEquals("PARTIAL",operations.create(nextInput(id,"等待8秒")).get("status"));
  }
  repaired.set(true);Map<String,Object> done;
  if(path.equals("recover"))done=operations.recover(sid,pid,id,0);
  else{operations.cancel(sid,pid,id,0);id+="-ready";done=operations.create(nextInput(id,"等待8秒"));}
  assertEquals("COMPLETED",done.get("status"),"one recovery must settle every already-known failed member");assertEquals(2,choices.get(),"accepted NPC choices are retained");
  var saved=json.readTree(payload(id));assertTrue(saved.path("pendingWorldFailures").isEmpty());
  for(String text:List.of("甲完成原整理。","乙完成原整理。"))assertEquals(1,saved.path("events").findValuesAsText("text").stream().filter(text::equals).count());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void legacyUndeliveredFailureMarkersDoNotConsumeAnotherRecovery(boolean cancel)throws Exception{
  var choices=jointPendingWorld("legacy-error-markers");var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload("legacy-error-markers"));
  p.path("decisions").fields().forEachRemaining(entry->{if(!entry.getValue().path("action_text").asText().isBlank())p.withObject("pendingWorldFailures").put(entry.getKey(),"ongoing work needs remaining work");});
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id='legacy-error-markers'",p.toString(),sid);
  waitFor(8);Map<String,Object> done;
  if(cancel){operations.cancel(sid,pid,"legacy-error-markers",0);done=operations.create(nextInput("legacy-error-next","等待8秒"));}
  else done=operations.recover(sid,pid,"legacy-error-markers",0);
  assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get());assertTrue(story(done).contains("实际完成动作。"));
 }
 @Test void remainingRepairUsesOneUnambiguousSystemSchemaAndRetainsFrozenFacts()throws Exception{
  schedule(b,3600);doAnswer(call->response(choice("固定原选择","","","整理衣物"))).when(model).chatSubForJson(anyList());var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.size()>2){assertEquals(1,m.stream().filter(v->"system".equals(v.get("role"))).count());assertFalse(m.get(0).get("content").contains("返回JSON：{\"process\""));assertTrue(m.get(0).get("content").contains("唯一返回结构"));assertEquals(a,json.readTree(m.get(1).get("content")).path("actorId").asText());assertEquals("甲继续整理衣物。",json.readTree(m.get(2).get("content")).path("process").asText());calls.incrementAndGet();return response(Map.of("remainingSeconds",8));}
   if(m.get(0).get("content").contains("你结算")){var n=new LinkedHashMap<>(result("甲继续整理衣物。",4,List.of()));n.put("status","ONGOING");return response(n);}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("single-repair-schema")).get("status"));assertEquals(1,calls.get());
 }
 @Test void isolationMalformedSiblingDoesNotRegenerateHealthyWorldResult()throws Exception{
  var choices=isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();
   return response(result(role.equals(a)?"甲整理了衣领。":"乙检查了衣袖。",role.equals(a)&&attempt==0?31:4,List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("isolation-schema"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,choices.get());assertEquals(2,calls.get(a).get());assertEquals(1,calls.get(b).get(),"valid sibling is not another repair attempt");
  var saved=json.readTree(payload("isolation-schema"));assertEquals(1,saved.path("rejectedWorldCandidates").size());assertEquals(2,saved.path("events").findValuesAsText("kind").stream().filter("ACTION_RESULT"::equals).count());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"append","replace","default"})
 void futurePlanPolicyReplacesByDefaultAndAppendsOnlyExplicitly(String policy)throws Exception{
  boolean replace=!policy.equals("append");
  acceptedWork(25,List.of());schedule(a,3600);schedule(b,3600);var calls=new AtomicInteger();
  var busy=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",String.class,sid,a));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'other-busy','忙碌',?,'ROLE_PROCESS','',?,'',0)",sid,b,busy.toString(),scene);
  String other=json.writeValueAsString(Map.of("decision",choice("乙的计划","","","乙查看木棒"),"spoken",false));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'other-plan','计划',?,'ROLE_PLAN','',?,'',0)",sid,b,other,scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));if(!a.equals(facts.path("id").asText()))return response(choice("","","",""));
   int n=calls.getAndIncrement();var d=choice("自己的新打算","等我。",player,n==0?"检查衣袖":"检查腰带");if(policy.equals("default")&&n>0)d.put("work_update",Map.of("mode","WAIT"));else d.put("work_update",Map.of("mode","WAIT","replace_plans",n>0&&replace));return response(d);
  }).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("plan-first")).get("status"));
  // Keep the unrelated actor's pending plan in the database for the scope assertion.
  String speech="请按你现在的打算来。";var rt=jdbc.queryForMap("SELECT branch_epoch,revision FROM game_runtime WHERE session_id=?",sid);
  assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("plan-second",sid,pid,scene,speech,new ActionRequest.DialogueInput(a,speech),((Number)rt.get("branch_epoch")).longValue(),((Number)rt.get("revision")).longValue())).get("status"));
  assertEquals(replace?1:2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PLAN'",Integer.class,sid,a));
  assertEquals(replace?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_SUPERSEDED'",Integer.class,sid,a));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_SUPERSEDED'",Integer.class,sid,b));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='other-plan' AND source_type='ROLE_PLAN'",Integer.class,sid,b));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ROLE_SUPERSEDED","ROLE_STOPPED","SPEECH_STOPPED","ROLE_PENDING"})
 void internalRecoveryArchivesCannotBecomeKnowledgeOrSharedMessages(String type)throws Exception{
  String secret="未发生的内部候选与私有诊断",title="内部档案";
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'archive-test',?,?,?,'',?,'',0)",sid,a,title,secret,type,scene);
  long id=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='archive-test'",Long.class,sid,a);
  assertFalse(knowledge.list(sid,a).toString().contains(secret));assertFalse(knowledge.privateContext(sid,a).contains(secret));
  assertTrue(knowledge.share(sid,a,b,id,"告诉乙："+title,scenes.findById(scene).orElseThrow(),Map.of(a,actors.findById(a).orElseThrow(),b,actors.findById(b).orElseThrow()),0).contains("内部角色状态"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TOLD'",Integer.class,sid,b));
 }
 @Test void repeatedFuturePlanUpdatesExecuteOnlyTheLatestAfterOriginalWork()throws Exception{
  acceptedWork(25,List.of());schedule(a,3600);schedule(b,3600);var choices=new AtomicInteger();var executed=new CopyOnWriteArrayList<String>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("","","",""));int n=choices.incrementAndGet();var d=choice("更新后续打算","好。",player,"后续检查第"+n+"处衣物");d.put("next_think_seconds",3600);return response(d);}).when(model).chatSubForJson(anyList());
  for(int n=1;n<=4;n++)assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("update-plan-"+n,sid,pid,scene,"你接下来有什么打算？",new ActionRequest.DialogueInput(a,"你接下来有什么打算？"),0L,((Number)jdbc.queryForMap("SELECT revision FROM game_runtime WHERE session_id=?",sid).get("revision")).longValue())).get("status"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PLAN'",Integer.class,sid,a));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_SUPERSEDED'",Integer.class,sid,a));
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待30秒","seconds",30))));String attempt=json.readTree(m.get(1).get("content")).path("attempt").asText();executed.add(attempt);return response(result("甲完成最新的后续检查。",1,List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("finish-latest-plan","等待30秒")).get("status"));assertEquals(List.of("后续检查第4处衣物"),executed);assertEquals(4,choices.get());
 }
 void isolationCommitFailure(boolean recover,boolean cancel)throws Exception{
  var choices=isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();
   boolean wrong=role.equals(b)&&attempt<(recover||cancel?2:1);String current=facts.path("resources").path(role).path("body").path("activity").asText();
   return response(result(role.equals(a)?"甲独立整理好衣领。":"乙独立整理好衣袖。",4,List.of(Map.of("kind","BODY","actorId",role,"before",Map.of("activity",wrong?"不存在的原状态":current),"after",Map.of("activity",role.equals(a)?"衣领已整理":"衣袖已整理")))));
  }).when(model).chatForJson(anyList());
  String id="isolation-commit-"+recover+"-"+cancel;var initial=operations.create(talk(id));
  if(recover||cancel){
   assertEquals("PARTIAL",initial.get("status"));assertEquals(1,calls.get(a).get());assertEquals(2,calls.get(b).get());
   assertEquals("",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"),"the closed group rolled back healthy effects too");
   assertFalse(story(initial).contains("甲独立整理好衣领"));
   if(cancel){
    operations.cancel(sid,pid,id,0);var pending=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PENDING'",String.class,sid,a);assertEquals(1,pending.size());assertEquals("甲独立整理好衣领。",json.readTree(pending.get(0)).path("executions").path("process").asText());
    doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待8秒","seconds",8))));var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();assertEquals(b,role,"healthy carried result must be reused");assertEquals("body condition changed",facts.path("rejectedResultReason").asText());calls.get(b).incrementAndGet();return response(result("乙独立整理好衣袖。",4,List.of()));}).when(model).chatForJson(anyList());
    initial=operations.create(nextInput("isolation-after-cancel","等待8秒"));
   }else initial=operations.recover(sid,pid,id,0);
  }
  assertEquals("COMPLETED",initial.get("status"));assertEquals(1,calls.get(a).get());assertEquals(recover||cancel?3:2,calls.get(b).get());assertEquals(2,choices.get(),"accepted decisions are not rerolled");
  assertEquals("衣领已整理",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  long count=json.readTree(payload(cancel?"isolation-after-cancel":id)).path("events").findValuesAsText("text").stream().filter("甲独立整理好衣领。"::equals).count();assertEquals(1,count);
 }
 @Test void isolationClosedGroupRollbackReusesHealthyCandidate()throws Exception{isolationCommitFailure(false,false);}
 @Test void isolationPartialRecoveryReusesHealthyCandidate()throws Exception{isolationCommitFailure(true,false);}
 @Test void isolationCancelCarriesHealthyCandidateToNextOperation()throws Exception{isolationCommitFailure(false,true);}
 @Test void isolationModelErrorAndProtocolErrorKeepSeparateJobIdentities()throws Exception{
  isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();if(role.equals(a)&&attempt==0)throw new LLMApiException("isolated API failure",false);
   if(role.equals(b)&&attempt>0)assertEquals("execution exceeds intervention boundary",facts.path("rejectedResultReason").asText());return response(result("各自完成整理。",role.equals(b)&&attempt==0?31:4,List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("isolation-mixed-errors"));assertEquals("PARTIAL",partial.get("status"));var saved=json.readTree(payload("isolation-mixed-errors"));assertTrue(saved.path("pendingJob").asText().startsWith(a+"|"));assertTrue(saved.path("modelFailures").get(0).path("job").asText().startsWith(a+"|"));assertTrue(saved.path("rejectedWorldCandidates").get(0).path("job").asText().startsWith(b+"|"));assertEquals(1,calls.get(a).get(),"real API failure does not retry automatically");
  assertEquals("COMPLETED",operations.recover(sid,pid,"isolation-mixed-errors",0).get("status"));assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());
 }
 @Test void isolationEachRoleMayRepairItsOwnTimingFormatOnce()throws Exception{
  var calls=new ConcurrentHashMap<String,AtomicInteger>();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();var decision=choice("保留原想法","","","整理自己的衣物");decision.put("speech_timing",attempt==0?"INVALID":"BEFORE_ACTION");return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("isolation-role-format"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());var saved=json.readTree(payload("isolation-role-format"));assertEquals(2,saved.path("roleRepairAttempts").asInt());assertEquals(2,saved.path("roleRepairByJob").size());
 }
 @Test void isolationEachWorldJobMayRepairMissingRemainingWorkOnce()throws Exception{
  isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();var candidate=new LinkedHashMap<String,Object>(result("各自继续整理。",4,List.of()));candidate.put("status","ONGOING");if(attempt>0)candidate.put("remainingSeconds",20);return response(candidate);}).when(model).chatForJson(anyList());
  var done=operations.create(talk("isolation-world-format"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());var saved=json.readTree(payload("isolation-world-format"));assertEquals(2,saved.path("repairAttempts").asInt());assertEquals(2,saved.path("worldFormatRepairByJob").size());
 }
 @Test void isolationLegacyRepairCountIsNotResetByPerJobMigration()throws Exception{
  var count=ConversationOperationService.class.getDeclaredMethod("repairCount",com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,String.class,String.class);count.setAccessible(true);
  var packet=json.createObjectNode().put("repairAttempts",1);assertEquals(1,count.invoke(null,packet,"worldFormatRepairByJob","repairAttempts",a+"|work"));
  var record=ConversationOperationService.class.getDeclaredMethod("recordRepair",com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,String.class,String.class);record.setAccessible(true);packet.put("repairAttempts",0);record.invoke(null,packet,"worldFormatRepairByJob","repairAttempts",a+"|work");assertEquals(1,count.invoke(null,packet,"worldFormatRepairByJob","repairAttempts",a+"|work"));assertEquals(0,count.invoke(null,packet,"worldFormatRepairByJob","repairAttempts",b+"|work"));
  var world=ConversationOperationService.class.getDeclaredMethod("worldRepairCount",com.fasterxml.jackson.databind.node.ObjectNode.class,String.class);world.setAccessible(true);assertEquals(0,world.invoke(null,packet,b+"|work"),"new format repair does not consume another job's mechanism retry");var old=json.createObjectNode().put("repairAttempts",1);assertEquals(1,world.invoke(null,old,b+"|work"),"legacy consumed mechanism budget is not reset");
 }
 @Test void isolationMechanismRepairsAreBoundedForEachActorIndependently()throws Exception{
  var choices=isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();String affected=attempt==0?player:role;
   return response(result("各自整理了衣领。",4,List.of(Map.of("kind","BODY","actorId",affected,"before",Map.of("activity",""),"after",Map.of("activity","衣领已整理")))));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("isolation-mechanism"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,choices.get());assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());var saved=json.readTree(payload("isolation-mechanism"));assertEquals(2,saved.path("worldRevalidationAttempts").asInt());assertEquals(2,saved.path("worldRevalidationByJob").size());assertEquals("",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
 }
 @Test void isolationStalePlayerSceneCannotCommitBodyFacilityTransferOrPlacement()throws Exception{
  jdbc.update("UPDATE god_profile SET inventory=? WHERE god_id=?",jsonArray("布条"),player);String ref=itemState.owned(sid,actors.findById(player).orElseThrow()).get(0).ref();var oldScene=scenes.findById(scene).orElseThrow();String remote=scene+"_remote";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处','[]','远处的现场',1,0)",remote,sid);jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE player_id=?",remote,pid);jdbc.update("UPDATE game_session SET current_scene_id=? WHERE session_id=?",remote,sid);
  var changes=List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("activity",""),"after",Map.of("activity","远程行动")),Map.of("kind","FACILITY","objectName","绳结","before","","after","已解开"),Map.of("kind","TRANSFER","fromId",player,"toId",a,"itemRef",ref,"mode","GIFT"),Map.of("kind","ITEM_POSITION","itemRef",ref,"before","","after","石柱旁"));
  for(var effect:changes){var candidate=json.valueToTree(result("旧场景候选不能执行。",2,List.of(effect)));var failure=assertThrows(IllegalArgumentException.class,()->effects.apply(sid,oldScene,player,player,candidate,new HashSet<>()));assertEquals("actor left scene",failure.getMessage());}
  assertEquals("",physical.view(sid,actors.findById(player).orElseThrow(),oldScene).get("activity"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));assertEquals("",placements.position(sid,player,ref));assertTrue(itemState.owned(sid,actors.findById(player).orElseThrow()).stream().anyMatch(item->ref.equals(item.ref())));
 }
 @Test void isolationAllRejectedCandidatesConsumeTheirOwnRetryAndGetOwnReason()throws Exception{
  isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();if(attempt>0)assertEquals("execution exceeds intervention boundary",facts.path("rejectedResultReason").asText());return response(result("各自完成整理。",attempt==0?31:4,List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("isolation-all-invalid"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());var saved=json.readTree(payload("isolation-all-invalid"));assertEquals(2,saved.path("worldRevalidationByJob").size());assertEquals(2,saved.path("worldRevalidationAttempts").asInt());assertTrue(saved.path("pendingWorldFailures").isEmpty());
 }
 @Test void isolationFailedFormatRepairStillPersistsHealthySiblingResponse()throws Exception{
  var choices=isolationChoices();var calls=new ConcurrentHashMap<String,AtomicInteger>();var repairs=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();if(m.size()>2){repairs.incrementAndGet();throw new LLMApiException("format repair unavailable",false);}int attempt=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();var candidate=new LinkedHashMap<String,Object>(result(role.equals(a)?"甲整理完衣领。":"乙整理完衣袖。",4,List.of()));if(role.equals(a)&&attempt==0)candidate.put("status","ONGOING");return response(candidate);}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("isolation-repair-api"));assertEquals("PARTIAL",partial.get("status"));var saved=json.readTree(payload("isolation-repair-api"));assertEquals(1,saved.path("executions").size());assertEquals("乙整理完衣袖。",saved.path("executions").elements().next().path("process").asText());assertEquals("WORLD_REPAIR",saved.path("modelFailures").get(0).path("phase").asText());assertEquals(1,repairs.get());assertEquals(1,calls.get(b).get());
  var done=operations.recover(sid,pid,"isolation-repair-api",0);assertEquals("COMPLETED",done.get("status"));assertEquals(2,choices.get());assertEquals(2,calls.get(a).get());assertEquals(1,calls.get(b).get());assertEquals(1,repairs.get());
 }
 @Test void unconsciousRecoveryCannotAlsoReleaseRestraintsOrMove()throws Exception{
  var state=Map.of("awake",false,"bound",true,"activity","resting","position","floor");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'body:v2','body',?,'BODY_STATE','',?,'',0)",sid,player,json.writeValueAsString(state),scene);
  var location=scenes.findById(scene).orElseThrow();
  for(String field:List.of("bound","position")){
   var before=new LinkedHashMap<String,Object>();before.put("awake",false);before.put(field,state.get(field));
   var after=new LinkedHashMap<String,Object>();after.put("awake",true);after.put(field,field.equals("bound")?false:"door");
   var packet=json.valueToTree(result("recovery",2,List.of(Map.of("kind","BODY","actorId",player,"before",before,"after",after))));
   assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,player,player,packet,new HashSet<>()));
   assertEquals(false,physical.view(sid,actors.findById(player).orElseThrow(),location).get("awake"));
  }
  var valid=json.valueToTree(result("recovery",2,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("awake",false),"after",Map.of("awake",true)))));
  effects.apply(sid,location,player,player,valid,new HashSet<>());
  var actual=physical.view(sid,actors.findById(player).orElseThrow(),location);assertEquals(true,actual.get("awake"));assertEquals(true,actual.get("bound"));assertEquals("floor",actual.get("position"));
 }
 final List<String> rolePrompts=new CopyOnWriteArrayList<>();final AtomicInteger actionCalls=new AtomicInteger();
 void boundedDurationRepair(boolean repeated)throws Exception{
  var calls=new AtomicInteger();var choices=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(a.equals(self.path("id").asText())){choices.incrementAndGet();return response(choice("原方法","","","检查绳结"));}return response(choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){var facts=json.readTree(m.get(1).get("content"));int index=calls.getAndIncrement();assertEquals(index==0?"":"execution exceeds intervention boundary",facts.path("rejectedResultReason").asText());return response(result("甲实际检查绳结。",index==0 || repeated?31:3,List.of()));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  String id="duration-"+repeated;var actual=operations.create(talk(id));assertEquals(repeated?"PARTIAL":"COMPLETED",actual.get("status"));assertEquals(1,choices.get());assertEquals(2,calls.get());var saved=json.readTree(payload(id));assertEquals(1,saved.path("worldRevalidationAttempts").asInt());assertEquals(repeated?2:1,saved.path("rejectedWorldCandidates").size());if(repeated)assertFalse(story(actual).contains("甲实际检查绳结"));
 }
 @Test void overlongWorldSegmentHasOneBoundedRepairWithoutChangingRole()throws Exception{boundedDurationRepair(false);}
 @Test void repeatedOverlongSegmentStopsWithoutFakeTimeClamping()throws Exception{boundedDurationRepair(true);}
 @Test void executionIssueBelongsToItsOwnJobInsteadOfOtherActor()throws Exception{
  var packet=json.createObjectNode();packet.put("sceneId",scene);packet.put("playerRole",player);packet.put("lastExecutionIssue","unrelated operation error");packet.putObject("jobTimes");packet.putObject("decisions");packet.putArray("queue");packet.putObject("worldIssuesByJob").put(a+"|job","execution exceeds intervention boundary");
  var method=ConversationOperationService.class.getDeclaredMethod("executionContext",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,String.class);method.setAccessible(true);
  @SuppressWarnings("unchecked") var own=(List<Map<String,String>>)method.invoke(operations,sid,packet,a+"|job","检查绳结");@SuppressWarnings("unchecked") var other=(List<Map<String,String>>)method.invoke(operations,sid,packet,b+"|job","观察石柱");
  assertEquals("execution exceeds intervention boundary",json.readTree(own.get(1).get("content")).path("rejectedResultReason").asText());assertFalse(json.readTree(other.get(1).get("content")).has("rejectedResultReason"));
 }
 void progressBeforeDeferral(boolean repeated,boolean legacy)throws Exception{
  schedule(a,1000);schedule(b,1000);var actual=new LinkedHashMap<>(result("之前已执行了一段打火，尚未点燃。",8,List.of()));actual.put("status","ONGOING");actual.put("remainingSeconds",22);
  var work=Map.of("attempt","继续打火","actual",actual,"lastEndSeconds",0);
  if(legacy){var d=choice("原打火方法","","","继续打火");d.put("continuation",work);var record=Map.of("decision",d,"execution",actual,"timing",Map.of("start",0,"executionStart",-6,"sceneId",scene),"spoken",true,"executed",false,"background",true);
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'legacy:invalid-progress','工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(record),scene);
  }else jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'v2:work','工作',?,'ROLE_TASK','',?,'',0)",sid,a,json.writeValueAsString(work),scene);
  var calls=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待3秒","seconds",3))));
   if(m.get(0).get("content").contains("你结算")){var facts=json.readTree(m.get(1).get("content"));assertEquals(22,facts.path("acceptedWork").path("actual").path("remainingSeconds").asInt());int index=calls.getAndIncrement();var n=new LinkedHashMap<>(result("未提交的打火过程。",8,List.of()));n.put("status","ONGOING");n.put("remainingSeconds",!legacy && (index==0 || repeated)?22:14);return response(n);}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  String id="progress-"+repeated+"-"+legacy;var done=operations.create(nextInput(id,"等待3秒"));assertEquals(repeated?"PARTIAL":"COMPLETED",done.get("status"));assertTrue(rolePrompts.isEmpty(),"world repair never reselects the role");assertEquals(legacy?1:2,calls.get());var saved=json.readTree(payload(id));assertEquals(1,saved.path("worldRevalidationAttempts").asInt());assertFalse(story(done).contains("未提交的打火过程"));
  var pending=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",sid,a);
  if(repeated){assertTrue(pending.isEmpty());assertEquals(2,saved.path("rejectedWorldCandidates").size());assertEquals(0L,done.get("storySeconds"));}
  else {assertEquals(3L,done.get("storySeconds"));assertEquals(1,pending.size());assertEquals(14,json.readTree(pending.get(0).get("content").toString()).path("execution").path("remainingSeconds").asInt());}
 }
 @Test void invalidRemainingWorkIsRevalidatedBeforeCrossRoundCache()throws Exception{progressBeforeDeferral(false,false);}
 @Test void repeatedNonProgressCannotBecomeDeferredWork()throws Exception{progressBeforeDeferral(true,false);}
 @Test void legacyInvalidProgressCacheIsRevalidatedWithoutNewRoleChoice()throws Exception{progressBeforeDeferral(false,true);}
 void revalidateState(String kind,boolean repeated)throws Exception{
  var choices=new AtomicInteger();var calls=new AtomicInteger();String id=kind+"-"+repeated;
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("继续自己的事","","",""));choices.incrementAndGet();return response(choice("保持原选择","不借。",player,"检查绳结"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){
   boolean wrong=calls.getAndIncrement()==0 || repeated;var facts=json.readTree(m.get(1).get("content"));Object effect;
   if(kind.equals("BODY"))effect=Map.of("kind","BODY","actorId",a,"before",Map.of("activity",wrong?"失效的原状态":facts.path("resources").path(a).path("body").path("activity").asText()),"after",Map.of("activity","已经检查绳结"));
   else effect=Map.of("kind","FACILITY","objectName","绳结","before",wrong?"失效的原状态":"","after","外层被压平，仍未解开");
   return response(result("甲检查了绳结的外层，未解开。",3,List.of(effect)));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var actual=operations.create(talk(id));assertEquals(repeated?"PARTIAL":"COMPLETED",actual.get("status"));assertEquals(1,choices.get());assertEquals(2,calls.get());var saved=json.readTree(payload(id));assertEquals(1,saved.path("worldRevalidationAttempts").asInt());assertEquals(repeated?2:1,saved.path("rejectedWorldBatches").size());
  if(kind.equals("BODY"))assertEquals(repeated?"":"已经检查绳结",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  else assertEquals(repeated?0:1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
  int speech=0,actions=0;for(var e:saved.path("events")){if("不借。".equals(e.path("speech").asText()))speech++;if(e.path("text").asText().contains("检查了绳结的外层"))actions++;}assertEquals(1,speech,"already delivered speech survives later effect rejection exactly once");assertEquals(repeated?0:1,actions);
 }
 @Test void bodyStateMismatchRevalidatesWithoutReselectingActor()throws Exception{revalidateState("BODY",false);}
 @Test void repeatedBodyMismatchStopsWithoutEffect()throws Exception{revalidateState("BODY",true);}
 @Test void facilityStateMismatchRevalidatesWithoutReselectingActor()throws Exception{revalidateState("FACILITY",false);}
 @Test void repeatedFacilityMismatchStopsWithoutEffect()throws Exception{revalidateState("FACILITY",true);}
 @Test void malformedBodyFieldDoesNotMasqueradeAsChangedState()throws Exception{
  var calls=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("","","","检查绳结"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){calls.incrementAndGet();return response(result("无效效果不能发生。",3,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("unknownField",""),"after",Map.of("unknownField","new")))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("invalid-body-schema"));assertEquals("PARTIAL",partial.get("status"));assertEquals(1,calls.get());var saved=json.readTree(payload("invalid-body-schema"));assertEquals("invalid body field",saved.path("lastExecutionIssue").asText());assertEquals(0,saved.path("worldRevalidationAttempts").asInt());assertFalse(story(partial).contains("无效效果"));
 }
 @Test void blockedAttemptCommitsActualToolMovementOnceAndKeepsOutcome()throws Exception{
  String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("按原方法试探","不借。",player,"拿起短刀试探绳结，但不切开"):choice("继续自己的事","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){calls.incrementAndGet();var n=new LinkedHashMap<>(result("甲将短刀拿到手中，绳结太紧，未能解开。",3,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","","after","右手"))));n.put("status","BLOCKED");n.put("observations",List.of(Map.of("channel","VISUAL","text","甲将短刀拿到手中，绳结太紧，未能解开。","action","试探绳结","status","BLOCKED")));return response(n);}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("blocked-real-process"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,calls.get());assertEquals("右手",placements.position(sid,a,ref));
  var saved=json.readTree(payload("blocked-real-process"));int count=0;for(var e:saved.path("events"))if(e.path("text").asText().contains("绳结太紧")){assertEquals("BLOCKED",e.path("status").asText());count++;}assertEquals(1,count);
 }
 @Test void blockedOutcomeCannotBypassOwnershipOrStalePlacement()throws Exception{
  String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();var location=scenes.findById(scene).orElseThrow();
  var n=new LinkedHashMap<>(result("受阻前取用短刀。",2,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","","after","右手"))));n.put("status","BLOCKED");
  assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,b,player,json.valueToTree(n),new HashSet<>()));assertEquals("",placements.position(sid,a,ref));
  n.put("effects",List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","不存在的位置","after","右手")));
  assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,a,player,json.valueToTree(n),new HashSet<>()));assertEquals("",placements.position(sid,a,ref));
 }
 @Test void blockedUnconsciousActorCannotObserveOrUseTools()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'body:v2','body',?,'BODY_STATE','',?,'',0)",sid,a,json.writeValueAsString(Map.of("awake",false,"bound",false,"position","floor","activity","resting")),scene);
  var location=scenes.findById(scene).orElseThrow();var n=new LinkedHashMap<>(result("因昏迷未能执行。",2,List.of()));n.put("status","BLOCKED");effects.apply(sid,location,a,player,json.valueToTree(n),new HashSet<>());
  n.put("effects",List.of(Map.of("kind","OBSERVE","actorId",a,"sceneId",scene,"scope","VISIBLE_SCENE")));assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,a,player,json.valueToTree(n),new HashSet<>()));
  n.put("effects",List.of());n.put("uses",List.of(itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref()));assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,a,player,json.valueToTree(n),new HashSet<>()));
 }
 @Test void invalidWorldCandidateRetainsRawResponseAndFrozenInputWithoutCommitting()throws Exception{
  doAnswer(call->response(choice("原选择","","","检查绳结"))).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(result("这份无效候选不能成为剧情。",31,List.of()));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("retain-invalid-world"));assertEquals("PARTIAL",partial.get("status"));var rejected=json.readTree(payload("retain-invalid-world")).path("rejectedWorldCandidates");assertFalse(rejected.isEmpty());
  var record=rejected.get(0);assertEquals("execution exceeds intervention boundary",record.path("issue").asText());assertEquals(31,json.readTree(record.path("rawResponse").asText()).path("durationSeconds").asInt());assertTrue(record.path("input").get(1).path("content").asText().contains("检查绳结"));assertEquals(record.path("job").asText(),json.readTree(payload("retain-invalid-world")).path("rejectedWorldBatches").get(0).path("failedJob").asText());assertFalse(story(partial).contains("这份无效候选"));
 }
 LLMResponse response(Object value)throws Exception{String raw=json.writeValueAsString(value);return new LLMResponse(raw,raw,0,0,0,0);}
 Map<String,Object> choice(String thought,String speech,String target,String action){var n=new LinkedHashMap<String,Object>();n.put("inner_thought",thought);n.put("speech",speech);n.put("target_god_id",target);n.put("action_text",action);n.put("speech_timing","BEFORE_ACTION");return n;}
 @Test void expiredPendingWorkRechecksFromNowWithoutReplayingItsSpeech()throws Exception{
  jdbc.update("INSERT INTO game_runtime(session_id,story_seconds) VALUES(?,10) ON DUPLICATE KEY UPDATE story_seconds=10",sid);schedule(a,3600);schedule(b,3600);var old=Map.of("decisions",choice("保留自己原来的考虑","旧话已经说过了。",player,"整理衣领"),"executions",result("过时未提交的结果。",3,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("activity",""),"after",Map.of("activity","过时状态")))),"jobTimes",Map.of("start",0,"executionStart",0,"sceneId",scene),"spokenJobs",true,"executedJobs",false);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'expired:pending','待提交工作',?,'ROLE_PENDING','',?,'',0)",sid,a,json.writeValueAsString(old),scene);var worldCalls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待三秒","seconds",3))));var facts=json.readTree(m.get(1).get("content"));worldCalls.incrementAndGet();assertEquals(10,facts.path("resumeFromSeconds").asLong());assertEquals("整理衣领",facts.path("attempt").asText());return response(result("甲按原来的想法重新核对现场，整理好衣领。",2,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("activity",""),"after",Map.of("activity","衣领已整理")))));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("expired-pending-work","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(13L,done.get("storySeconds"));assertEquals(1,worldCalls.get());assertEquals("衣领已整理",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));assertFalse(story(done).contains("旧话已经说过"));assertFalse(story(done).contains("过时未提交"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void routineStoryCannotSpeakAPlayerPromiseOrSpendANpcResponseOnIt()throws Exception{
  schedule(a,3600);schedule(b,3600);String input="了解工匠甲的伤情";var replies=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String prompt=m.get(0).get("content");if(prompt.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"targetId",a,"routineConversation",true))));if(prompt.contains("连贯故事节奏规划"))return response(Map.of("kind","SPEECH","routineKind","CARE","targetId",a,"text","我承诺30秒内把短刀送给你。"));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  doAnswer(call->{replies.incrementAndGet();return response(choice("由我判断","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("unauthorized-routine-promise",input));assertEquals("COMPLETED",done.get("status"));assertEquals(0,replies.get());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));assertFalse(story(done).contains("我承诺"));assertFalse(json.valueToTree(done).path("ruleSnapshot").path("storyProgress").path("requiresInput").asBoolean());
 }
 ConversationOperationService.Request promiseSpeech(String id,String speech){var r=nextInput(id,speech);return new ConversationOperationService.Request(id,sid,pid,scene,speech,new ActionRequest.DialogueInput(a,speech),r.branchEpoch(),r.expectedRevision());}
 JsonNode promiseFor(String owner)throws Exception{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE' ORDER BY knowledge_id DESC LIMIT 1",String.class,sid,owner));}
 @Test void deliveredPlayerPromiseUsesActualSpeechEndAndDoesNotReachBystander()throws Exception{
  String speech="我承诺三十秒内把布条送给你。";var done=operations.create(promiseSpeech("player-pledge",speech));assertEquals("COMPLETED",done.get("status"));var pledge=promiseFor(player);
  assertEquals("OPEN",pledge.path("status").asText());assertEquals("STORY_SECONDS",pledge.path("timeBasis").asText());assertEquals(30,pledge.path("dueSeconds").asLong()-pledge.path("createdSeconds").asLong());assertEquals("布条",pledge.path("value").asText());assertEquals(pledge,promiseFor(a));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",Integer.class,sid,b));assertTrue(knowledge.privateContext(sid,a).contains("故事第"));
  var packet=json.readTree(payload("player-pledge"));var spoken=java.util.stream.StreamSupport.stream(packet.path("events").spliterator(),false).filter(e->"UTTERANCE".equals(e.path("kind").asText()) && player.equals(e.path("actorId").asText())).findFirst().orElseThrow();assertEquals(spoken.path("endSeconds").asLong(),pledge.path("createdSeconds").asLong());
 }
 @Test void deliveredNpcPromiseIsCapturedAndActualGiftFulfillsIt()throws Exception{
  String speech="我答应一分钟内把短刀送给你。";var original=actors.findById(a).orElseThrow();String ref=itemState.owned(sid,original).get(0).ref();var choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();if(a.equals(role)&&choices.getAndIncrement()==0)return response(choice("我自主决定赠予","我答应一分钟内把短刀送给你。",player,"赠送自己的短刀"));return response(choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->response(result("甲把短刀交给了你。",2,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","GIFT"))))).when(model).chatForJson(anyList());
  var done=operations.create(talk("npc-pledge-gift"));assertEquals("COMPLETED",done.get("status"));var pledge=promiseFor(player);assertEquals(speech,pledge.path("quote").asText());assertEquals("FULFILLED",pledge.path("status").asText());assertTrue(itemState.owned(sid,actors.findById(player).orElseThrow()).stream().anyMatch(i->ref.equals(i.ref())));
 }
 @Test void npcWhisperPromiseBelongsOnlyToItsActuallyHearingParties()throws Exception{
  String speech="我承诺30秒内把短刀送给你。";var decisions=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();var d=choice("","","","");if(a.equals(role)&&decisions.getAndIncrement()==0){d=choice("自己的私下决定",speech,b,"");d.put("speech_scope","WHISPER");}return response(d);}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("private-pledge"));assertEquals("COMPLETED",done.get("status"));assertEquals("OPEN",promiseFor(a).path("status").asText());assertEquals(promiseFor(a),promiseFor(b));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",Integer.class,sid,player));assertFalse(story(done).contains(speech));
 }
 @Test void parallelGiftAndPromiseAtTheSameInstantFulfillRegardlessOfBoundaryIdOrder()throws Exception{
  String speech="我承诺30秒内把短刀送给你。";double rate=(double)org.springframework.test.util.ReflectionTestUtils.getField(operations,"speechRate");int duration=(int)com.bs.narrative.simulation.SpeechTime.seconds(speech,rate);String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();var choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();var d=choice("","","","");if(a.equals(role)&&choices.getAndIncrement()==0){d=choice("边说边交给对方",speech,player,"赠送自己的短刀");d.put("speech_timing","PARALLEL");}return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->response(result("甲说话的同时把短刀交给了你。",duration,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","GIFT"))))).when(model).chatForJson(anyList());
  var done=operations.create(talk("parallel-pledge-gift"));assertEquals("COMPLETED",done.get("status"));assertEquals("FULFILLED",promiseFor(player).path("status").asText());assertEquals(promiseFor(a),promiseFor(player));
 }
 @Test void capturedRealQuotedAuthorizationReplaysThroughHearingPromiseAndConditionalMovement()throws Exception{
  replayQuotedConditionalMovement("/fixtures/quoted-promise-authorization.json");
 }
 @Test void capturedSharedConditionPrefixReplaysWithoutRepeatingTheWait()throws Exception{
  replayQuotedConditionalMovement("/fixtures/conditional-prefix-authorization.json");
 }
 @Test void capturedLeadingSubjectReplaysTheFullConditionalMovement()throws Exception{
  replayQuotedConditionalMovement("/fixtures/leading-subject-authorization.json");
 }
 @Test void recoveryReusesARevalidatedAuthorizationWithoutAnotherModelParse()throws Exception{
  replayQuotedConditionalMovement("/fixtures/leading-subject-authorization.json",true);
 }
 @Test void savedAuthorizationCannotBeReusedAfterContextOrStoryTimeChanges()throws Exception{
  var captured=json.readTree(getClass().getResourceAsStream("/fixtures/leading-subject-authorization.json"));String original=captured.path("rawText").asText();var p=json.createObjectNode().put("playerInput",original).put("endSeconds",0);var facts=Map.of("input",original,"inputPurpose","ACTION_INPUT");var messages=List.of(Map.of("role","system","content",ConditionWaitService.AUTHORIZATION_CONTRACT),Map.of("role","user","content",json.writeValueAsString(facts)));var attempt=p.putArray("authorizationAttempts").addObject().put("rawResponse",captured.toString()).put("atSeconds",0);attempt.set("input",json.valueToTree(messages));var method=ConversationOperationService.class.getDeclaredMethod("reusableAuthorization",com.fasterxml.jackson.databind.node.ObjectNode.class,List.class,Map.class);method.setAccessible(true);var names=Map.of("npc_167bd2732d7a436f9b2f4e369bd2","陆青梧");assertNotNull(method.invoke(operations,p,messages,names));
  var changed=List.of(messages.get(0),Map.of("role","user","content",json.writeValueAsString(Map.of("input",original,"inputPurpose","ACTION_REQUEST"))));assertNull(method.invoke(operations,p,changed,names));p.put("endSeconds",1);assertNull(method.invoke(operations,p,messages,names));p.put("endSeconds",0);attempt.put("rawResponse",json.writeValueAsString(Map.of("steps",List.of(Map.of("type","ACTION","text",original,"targetId","unknown")))));assertNull(method.invoke(operations,p,messages,names));attempt.put("rawResponse","{}");assertNull(method.invoke(operations,p,messages,names));
 }
 void replayQuotedConditionalMovement(String resource)throws Exception{
  replayQuotedConditionalMovement(resource,false);
 }
 void replayQuotedConditionalMovement(String resource,boolean migration)throws Exception{
  var captured=json.readTree(getClass().getResourceAsStream(resource));captured=json.readTree(captured.toString().replace("npc_167bd2732d7a436f9b2f4e369bd2",a).replace("陆青梧","工匠甲"));final JsonNode authorization=captured;String input=captured.path("rawText").asText();schedule(a,3600);schedule(b,3600);var moved=new AtomicInteger();var parses=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String prompt=m.get(0).get("content");if(prompt.contains("玩家输入授权解析")){parses.incrementAndGet();return response(authorization);}if(prompt.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","这一段交流已经自然结束。"));if(prompt.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));assertTrue(facts.path("attempt").asText().contains("不碰或交出任何物品"));moved.incrementAndGet();return response(result("你向空地东侧退了一步，保持与驮马的距离。",2,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","空地东侧外沿，远离驮马")))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();return response(a.equals(role)?choice("根据自己知道的路况回应","踩实的土路就在东侧，先看少苔的平整处。",player,""):choice("","","",""));}).when(model).chatSubForJson(anyList());
  Map<String,Object> done;int expectedParses=1;
  if(migration){var actual=(ConditionWaitService)org.springframework.test.util.ReflectionTestUtils.getField(operations,"waits");var legacy=new ConditionWaitService(){@Override public JsonNode normalizeAuthorization(String original,JsonNode supplied,Map<String,String> names){throw new IllegalArgumentException("legacy context coverage rejected the saved response");}};
   try{org.springframework.test.util.ReflectionTestUtils.setField(operations,"waits",legacy);assertEquals("PARTIAL",operations.create(storyRequest("captured-quoted-promise",input)).get("status"));}finally{org.springframework.test.util.ReflectionTestUtils.setField(operations,"waits",actual);}
   expectedParses=parses.get();assertEquals(2,expectedParses,"initial parsing and its single bounded repair both remain audited");done=operations.recover(sid,pid,"captured-quoted-promise",0);assertEquals(expectedParses,parses.get(),"recovery revalidates the saved response without another model parse");assertEquals(0,json.readTree(payload("captured-quoted-promise")).path("authorizationReusedFromAttempt").asInt(-1));
  }else done=operations.create(storyRequest("captured-quoted-promise",input));
  assertEquals("COMPLETED",done.get("status"));assertEquals(expectedParses,parses.get());assertEquals(1,moved.get());assertEquals("空地东侧外沿，远离驮马",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));assertEquals("NON_AGGRESSION",promiseFor(a).path("kind").asText());assertTrue(story(done).contains("踩实的土路就在东侧"));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
 }
 @Test void promiseToUnconsciousAddresseeAndUnspokenThoughtAreNotRegistered()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','昏迷','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,b,scene);var decisions=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String role=json.readTree(m.get(1).get("content")).path("id").asText();return response(a.equals(role)&&decisions.getAndIncrement()==0?choice("我承诺一分钟内把另一件东西送给玩家。","我承诺30秒内把短刀送给你。",b,""):choice("","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("unheard-pledge"));assertEquals("COMPLETED",done.get("status"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void rewindingAnActualOperationRemovesItsPromiseAndReplaysOnTheNewBranch()throws Exception{
  String speech="我承诺30秒内把布条送给你。";var first=operations.create(promiseSpeech("before-rewind-pledge",speech));assertEquals("COMPLETED",first.get("status"));assertEquals("OPEN",promiseFor(a).path("status").asText());checkpoints.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
  var replay=operations.create(promiseSpeech("after-rewind-pledge",speech));assertEquals("COMPLETED",replay.get("status"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void npcThinkingDuringLongWaitSeesTheAlreadyOverduePromise()throws Exception{
  String speech="我承诺十秒内把布条送给你。";var first=operations.create(promiseSpeech("before-wait-pledge",speech));assertEquals("COMPLETED",first.get("status"));long end=((Number)first.get("storySeconds")).longValue();jdbc.update("DELETE FROM role_knowledge WHERE session_id=? AND source_ref='v2:schedule'",sid);schedule(a,end+15);schedule(b,3600);waitFor(20);var seen=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(a.equals(self.path("id").asText())){seen.incrementAndGet();assertTrue(self.path("ownKnowledge").asText().contains("逾期未兑现"));assertTrue(self.path("ownKnowledge").asText().contains("原因待核实"));}return response(choice("由我自己判断未兑现的原因","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(nextInput("wait-overdue-pledge","等待20秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,seen.get());assertEquals("OVERDUE",promiseFor(a).path("status").asText());
 }
 Map<String,Object> result(String process,int seconds,List<?> effects){return Map.of("process",process,"observations",List.of(Map.of("channel","VISUAL","text",process,"action",process)),"durationSeconds",seconds,"effects",effects,"status","COMPLETED");}
 @BeforeEach void seed(){
  String suffix=UUID.randomUUID().toString().substring(0,8);sid="op_it_"+suffix;pid="op_user_"+suffix;player="op_p_"+suffix;a="op_a_"+suffix;b="op_b_"+suffix;scene="op_s_"+suffix;
  for(String role:List.of(player,a,b))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',15,15,15,15,25,75,?)",role,sid,role.equals(player)?"玩家":role.equals(a)?"工匠甲":"守望者乙",role.equals(a)?"[\"短刀\"]":"[]");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'石柱边',?,'工匠甲和守望者乙清醒且未被束缚。石柱后的绳结实际存在。',0,0)",scene,sid,jsonArray(a,b));
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,?,'unused',?,?,'[]')",pid,player,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",sid,pid,player,scene);checkpoints.initialize(sid,pid);
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"role model must run outside transaction");List<Map<String,String>> messages=call.getArgument(0);rolePrompts.add(messages.get(1).get("content"));return response(choice("自有打算","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"world/story/intent model must run outside transaction");List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
   if(prompt.contains("你结算")){actionCalls.incrementAndGet();return response(result("工匠甲整理了衣角。",4,List.of()));}
   if(prompt.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","检查绳结"))));
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
 }
 String jsonArray(String... values){try{return json.writeValueAsString(values);}catch(Exception e){throw new RuntimeException(e);}}
 void acceptedWork(int seconds,List<?> changes)throws Exception{
  var work=Map.of("decision",choice("原先自己的打算","","","整理衣物"),"execution",result("甲的原工作实际完成。",seconds,changes),"timing",Map.of("start",0,"executionStart",0,"sceneId",scene),"spoken",true,"executed",false,"background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'test:accepted-work','工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(work),scene);
 }
 ConversationOperationService.Request nextInput(String id,String text){var rows=jdbc.queryForList("SELECT * FROM game_runtime WHERE session_id=?",sid);var rt=rows.isEmpty()?Map.of("branch_epoch",0L,"revision",0L):rows.get(0);return new ConversationOperationService.Request(id,sid,pid,scene,text,null,((Number)rt.get("branch_epoch")).longValue(),((Number)rt.get("revision")).longValue());}
 void schedule(String role,long at)throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'v2:schedule','自主时钟',?,'ROLE_SCHEDULE','',?,'',0)",sid,role,json.writeValueAsString(Map.of("nextSeconds",at)),scene);
 }
 void waitFor(int seconds)throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待"+seconds+"秒","seconds",seconds))));
   if(m.get(0).get("content").contains("你结算"))return response(result("实际完成动作。",1,List.of()));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
 }
 @Test void autonomousClockRunsAtFiveAndTenDuringTwentySecondBodyWork()throws Exception{
  acceptedWork(20,List.of());schedule(a,5);schedule(b,3600);waitFor(11);var times=new CopyOnWriteArrayList<Long>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));assertEquals(a,self.path("id").asText());times.add(self.path("storySeconds").asLong());assertTrue(self.path("activeWork").toString().contains("整理衣物"));var d=choice("继续原有工作时思考","","","");d.put("next_think_seconds",5);return response(d);}).when(model).chatSubForJson(anyList());
  var done=operations.create(nextInput("independent-clock","等待11秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(11L,done.get("storySeconds"));assertEquals(List.of(5L,10L),times);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));assertFalse(story(done).contains("原工作实际完成"));
  var timer=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='v2:schedule'",String.class,sid,a));assertEquals(15L,timer.path("nextSeconds").asLong());
 }
 @Test void oldBodyCompletionCannotOverwriteNewMessageDecisionClock()throws Exception{
  acceptedWork(20,List.of());schedule(a,1000);schedule(b,3600);var chosenAt=new java.util.concurrent.atomic.AtomicLong();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));chosenAt.set(self.path("storySeconds").asLong());var d=choice("听到了仍不借","不借。",player,"");d.put("next_think_seconds",100);return response(d);}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("new-timer")).get("status"));waitFor(25);
  assertEquals("COMPLETED",operations.create(nextInput("finish-old-body","等待25秒")).get("status"));
  var timer=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='v2:schedule'",String.class,sid,a));assertEquals(chosenAt.get()+100,timer.path("nextSeconds").asLong());assertTrue(timer.path("decisionId").asText().startsWith("new-timer:"));
 }
 @Test void remoteAutonomousWorkChangesRemoteStateWithoutPlayerDelivery()throws Exception{
  String remote=scene+"_remote",secret="远处木架已整理";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'另一房间',?,'甲独自在整理木架。',1,0)",remote,sid,jsonArray(a));jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",jsonArray(b),scene);schedule(a,5);schedule(b,3600);waitFor(10);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));assertEquals(a,self.path("id").asText());assertEquals(5L,self.path("storySeconds").asLong());assertFalse(self.path("knownPeople").toString().contains(player));var d=choice("自己的工作","","","整理木架");d.put("next_think_seconds",100);return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待10秒","seconds",10))));if(system.contains("你结算"))return response(result(secret,2,List.of(Map.of("kind","FACILITY","objectName","木架","before","","after",secret))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("remote-clock","等待10秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(10L,done.get("storySeconds"));assertFalse(done.toString().contains(secret));
  assertTrue(jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND scene_id=? AND source_type='SCENE_STATE'",String.class,sid,remote).toString().contains(secret));assertFalse(jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT'",String.class,sid,player).toString().contains(secret));
 }
 @Test void timedReconsiderationCanInterruptAcceptedForegroundBody()throws Exception{
  AtomicInteger ownChoices=new AtomicInteger();var times=new CopyOnWriteArrayList<Long>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));times.add(self.path("storySeconds").asLong());var d=choice("自己的打算","","",ownChoices.getAndIncrement()==0?"整理衣物":"");d.put("next_think_seconds",5);if(ownChoices.get()>1){assertTrue(self.path("activeWork").toString().contains("整理衣物"));d.put("work_update",Map.of("mode","INTERRUPT"));d.put("next_think_seconds",100);}return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(result("原前台工作完成。",20,List.of()));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("interrupt-foreground"));assertEquals("COMPLETED",done.get("status"));assertEquals(List.of(2L,7L),times);assertFalse(story(done).contains("原前台工作完成"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PAUSED'",Integer.class,sid,a));
 }
 @Test void legacyAcceptedWorkSeedsMissingClockFromOriginalDecisionTime()throws Exception{
  acceptedWork(20,List.of());var raw=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_ref='test:accepted-work'",String.class,sid);var work=json.readTree(raw);((com.fasterxml.jackson.databind.node.ObjectNode)work.path("decision")).put("next_think_seconds",5);jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND source_ref='test:accepted-work'",work.toString(),sid);schedule(b,3600);waitFor(6);var times=new CopyOnWriteArrayList<Long>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));times.add(self.path("storySeconds").asLong());return response(choice("继续自己的工作","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(nextInput("legacy-clock","等待6秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(List.of(5L),times);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));
 }
 @Test void coTimedAutonomousThoughtSeesCompletedBodyEffects()throws Exception{
  acceptedWork(5,List.of(Map.of("kind","FACILITY","objectName","石柱","before","","after","绳结已经松开")));schedule(a,5);schedule(b,3600);waitFor(6);var times=new CopyOnWriteArrayList<Long>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));times.add(self.path("storySeconds").asLong());assertTrue(self.path("observedFacilities").toString().contains("绳结已经松开"));assertEquals(0,self.path("activeWork").size());return response(choice("看到自己已完成的结果","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(nextInput("same-time-clock","等待6秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(List.of(5L),times);
 }
 @Test void independentTimerAndBodySurvivePartialRecoveryWithoutReroll()throws Exception{
  acceptedWork(20,List.of());schedule(a,5);schedule(b,3600);waitFor(11);AtomicInteger calls=new AtomicInteger();var times=new CopyOnWriteArrayList<Long>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));long at=self.path("storySeconds").asLong();if(calls.incrementAndGet()>1)throw new IllegalArgumentException("test temporary invalid role packet");times.add(at);var d=choice("继续工作","","","");d.put("next_think_seconds",5);return response(d);}).when(model).chatSubForJson(anyList());
  var partial=operations.create(nextInput("recover-clock","等待11秒"));assertEquals("PARTIAL",partial.get("status"));assertEquals(List.of(5L),times);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));times.add(self.path("storySeconds").asLong());var d=choice("继续工作","","","");d.put("next_think_seconds",5);return response(d);}).when(model).chatSubForJson(anyList());
  var done=operations.recover(sid,pid,"recover-clock",0);assertEquals("COMPLETED",done.get("status"));assertEquals(11L,done.get("storySeconds"));assertEquals(List.of(5L,10L),times);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));
 }
 @Test void actionWaitActionCannotSkipDeferredWorkInSameOperation()throws Exception{
  acceptedWork(5,List.of(Map.of("kind","FACILITY","objectName","石柱","before","","after","绳結已松开")));
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","查看衣角"),Map.of("type","WAIT","text","等待6秒","seconds",6),Map.of("type","ACTION","text","再查看石柱"))));
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));if(facts.path("attempt").asText().equals("再查看石柱")){assertEquals(8L,facts.path("currentStorySeconds").asLong());assertTrue(facts.path("facilities").toString().contains("绳結已松开"));}return response(result("你完成此次查看。",2,List.of()));}
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("multi-step-work","查看衣角，等待6秒，再查看石柱"));assertEquals("COMPLETED",done.get("status"));assertEquals(10L,done.get("storySeconds"));
  var saved=json.readTree(payload("multi-step-work"));long count=0;for(var e:saved.path("events"))if(e.path("text").asText().equals("甲的原工作实际完成。")){count++;assertEquals(5L,e.path("endSeconds").asLong());}assertEquals(1L,count);
 }
 @Test void busyRoleCanPromiseLaterAndLoanOnceWithoutReplayingSpeech()throws Exception{
  acceptedWork(20,List.of());String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();AtomicInteger choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));choices.incrementAndGet();assertTrue(self.path("activeWork").toString().contains("整理衣物"));return response(choice("做完原工作再借","等我。",player,"把短刀借给玩家"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待25秒","seconds",25))));
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));assertEquals(a,facts.path("actorId").asText());assertEquals(20L,facts.path("currentStorySeconds").asLong());return response(result("甲把短刀实际借给玩家。",1,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))));}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var first=operations.create(talk("busy-plan"));assertEquals("COMPLETED",first.get("status"));assertTrue(story(first).contains("等我。"));assertTrue(((Number)first.get("storySeconds")).longValue()<20);assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PLAN'",Integer.class,sid));
  var last=operations.create(nextInput("finish-plan","等待25秒"));assertEquals("COMPLETED",last.get("status"));assertFalse(story(last).contains("等我。"));assertTrue(story(last).contains("实际借给玩家"));assertEquals(1,choices.get());assertEquals(List.of("短刀"),actors.findById(player).orElseThrow().getInventory());assertTrue(actors.findById(a).orElseThrow().getInventory().isEmpty());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PLAN'",Integer.class,sid));
 }
 @Test void watchingBodyChangeDoesNotDeliverActorsPrivatePercepts()throws Exception{
  String secret="只有甲从细看中辨出的私密线索";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(a.equals(self.path("id").asText())?choice("观察自己的细节","","","凝神细看"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(result("甲凝神细看。",1,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("percepts",List.of()),"after",Map.of("percepts",List.of(secret))))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("private-perception"));assertEquals("COMPLETED",done.get("status"));assertFalse(done.toString().contains(secret));
  assertTrue(physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("percepts").toString().contains(secret));
  for(String observer:List.of(player,b))assertFalse(jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT'",String.class,sid,observer).toString().contains(secret));
 }
 @Test void carriedSpeechCannotOverlapThisActorsEarlierCommittedSpeech()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(a.equals(self.path("id").asText())?choice("自己的话","先说一句。",player,""):choice("","","",""));}).when(model).chatSubForJson(anyList());
  var first=operations.create(talk("previous-voice"));assertEquals("COMPLETED",first.get("status"));long boundary=((Number)first.get("storySeconds")).longValue();String speech="随后补充的这句话。";
  var decision=new LinkedHashMap<>(choice("已接受的另一个决定",speech,player,"整理衣物"));decision.put("speech_timing","PARALLEL");
  var work=Map.of("decision",decision,"execution",result("旧工作完成。",20,List.of()),"timing",Map.of("start",boundary-1,"executionStart",boundary-1,"sceneId",scene),"spoken",false,"executed",false,"background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'late-voice','待发言',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(work),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待5秒","seconds",5))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var next=operations.create(nextInput("carry-voice","等待5秒"));assertEquals("COMPLETED",next.get("status"));int count=0;for(var e:json.readTree(payload("carry-voice")).path("events"))if(speech.equals(e.path("speech").asText())){assertTrue(e.path("startSeconds").asLong()>=boundary);count++;}assertEquals(1,count);
 }
 @Test void sameActorCannotSpeakTwoAcceptedUtterancesAtOnce()throws Exception{
  for(int i=0;i<2;i++){
   var work=Map.of("decision",choice("原有决定",i==0?"第一句话。":"第二句话。",player,"整理衣物"),"execution",result("旧工作完成。",20,List.of()),"timing",Map.of("start",0,"executionStart",0,"sceneId",scene),"spoken",false,"executed",false,"background",true);
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,"voice:"+i,json.writeValueAsString(work),scene);
  }
  var done=operations.create(talk("serialized-voice"));assertEquals("COMPLETED",done.get("status"));long previousEnd=0;int count=0;
  for(var e:json.readTree(payload("serialized-voice")).path("events"))if(a.equals(e.path("actorId").asText()) && "UTTERANCE".equals(e.path("kind").asText())){assertTrue(e.path("startSeconds").asLong()>=previousEnd);previousEnd=e.path("endSeconds").asLong();count++;}assertEquals(2,count);
 }
 @Test void npcReplyChainContinuesWithoutHoldingPlayerOperationOpenForever()throws Exception{
  AtomicInteger calls=new AtomicInteger();doAnswer(call->{calls.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));String role=self.path("id").asText();return response(choice("自己的交谈","继续讨论。",role.equals(a)?b:a,""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("npc-conversation"));assertEquals("COMPLETED",done.get("status"));assertTrue(((Number)done.get("storySeconds")).longValue()<=4);assertTrue(calls.get()<=6);
  assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type IN ('ROLE_PROCESS','ROLE_INBOX')",Integer.class,sid)>0,"future dialogue must remain as accepted work or unread mail");
 }
 @Test void roleFormatRepairMayNotRewriteRefusalOrAction()throws Exception{
  acceptedWork(20,List.of());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));var decision=new LinkedHashMap<>(choice("我自己不愿借",m.size()>2?"好，借你。":"不借。",player,""));decision.put("speech_timing",m.size()>2?"BEFORE_ACTION":"WAIT");return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("reject-reroll"));assertEquals("PARTIAL",done.get("status"));assertFalse(story(done).contains("借你"));assertTrue(json.readTree(payload("reject-reroll")).has("rejectedRoleCandidates"));assertEquals(1,json.readTree(payload("reject-reroll")).path("roleRepairAttempts").asInt());
  assertTrue(json.readTree(payload("reject-reroll")).path("executions").toString().contains("甲的原工作实际完成"),"role protocol failure must not erase an already obtained body candidate");
 }
 @Test void roleFormatRepairPreservesChoiceAndDoesNotLoseStimulus()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));var decision=new LinkedHashMap<>(choice("我自己不愿借","不借。",player,""));decision.put("speech_timing",m.size()>2?"BEFORE_ACTION":"WAIT");return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("format-only"));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("不借。"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_INBOX'",Integer.class,sid));
 }
 @Test void workFinishingDuringSpeechCannotStartPlanOrReplayThatSpeechEarly()throws Exception{
  acceptedWork(3,List.of());String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();String speech="我听见了，等我把手头的事情做好，再把短刀交给你暂用，你用完记得还给我。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(a.equals(self.path("id").asText())?choice("先说清楚条件",speech,player,"把短刀借给玩家"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){var facts=json.readTree(m.get(1).get("content"));assertTrue(facts.path("currentStorySeconds").asLong()>=2+com.bs.narrative.simulation.SpeechTime.seconds(speech,5.0));return response(result("甲实际交出短刀。",1,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var first=operations.create(talk("work-during-speech"));assertEquals("COMPLETED",first.get("status"));long quotes=0;for(var e:json.readTree(payload("work-during-speech")).path("events"))if(speech.equals(e.path("speech").asText()))quotes++;assertEquals(1L,quotes);assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
 }
 @Test void roleCanInterruptOwnWorkAndOldUncommittedOutcomeNeverReappears()throws Exception{
  acceptedWork(20,List.of());String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));var decision=new LinkedHashMap<>(choice("我先停下原工作","","","停下整理，把短刀借给玩家"));decision.put("work_update",Map.of("mode","INTERRUPT"));return response(decision);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待25秒","seconds",25))));if(system.contains("你结算"))return response(result("甲停下原工作，把短刀借给玩家。",1,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var first=operations.create(talk("interrupt-work"));assertEquals("COMPLETED",first.get("status"));assertTrue(((Number)first.get("storySeconds")).longValue()<20);assertFalse(story(first).contains("原工作实际完成"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PAUSED'",Integer.class,sid,a));
  var last=operations.create(nextInput("past-old-boundary","等待25秒"));assertEquals("COMPLETED",last.get("status"));assertFalse(story(last).contains("原工作实际完成"));assertEquals(List.of("短刀"),actors.findById(player).orElseThrow().getInventory());
 }
 void delivered(String observer,String ref,Object value)throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'亲历',?,'TURN_EVENT','',?,'',0)",sid,observer,ref,json.writeValueAsString(value),scene);
 }
 @Test void stateReducerKeepsNewObservationWhenOldEventArrivesLateAndSpeechLies()throws Exception{
  delivered(a,"new-state",Map.of("endSeconds",20,"effects",List.of(Map.of("kind","FACILITY","objectName","石柱","after","绳结已解开"))));
  delivered(a,"late-old",Map.of("endSeconds",5,"effects",List.of(Map.of("kind","FACILITY","objectName","石柱","after","绳结系紧"))));
  delivered(a,"lie",Map.of("recordType","UTTERANCE","endSeconds",30,"text","我已把绳结重新系紧","effects",List.of(Map.of("kind","FACILITY","objectName","石柱","after","绳结系紧"))));
  var facts=knownState.current(sid,a,scene);assertEquals(1,facts.size());assertEquals("绳结已解开",facts.get(0).get("value"));assertEquals(20L,facts.get(0).get("occurredAtSeconds"));assertEquals("new-state",facts.get(0).get("sourceEvent"));
  assertTrue(knownState.current(sid,b,scene).isEmpty());
 }
 @Test void actualPlacementIsCheckedAndRestoresWithoutInventingPossession()throws Exception{
  var holder=actors.findById(a).orElseThrow();String ref=itemState.owned(sid,holder).get(0).ref();
  var location=scenes.findById(scene).orElseThrow();
  assertEquals("",placements.position(sid,a,ref));
  var move=json.valueToTree(result("工匠甲把短刀搁在膝上。",2,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","","after","膝上"))));
  effects.apply(sid,location,a,player,move,new HashSet<>());
  assertEquals("膝上",placements.position(sid,a,ref));assertEquals(List.of("短刀"),actors.findById(a).orElseThrow().getInventory());
  assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,a,player,move,new HashSet<>()));
  assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,b,player,move,new HashSet<>()));
  String actualFacts=json.writeValueAsString(effects.facts(sid,location,a,player));assertTrue(actualFacts.contains("膝上"));
  assertTrue(knowledge.list(sid,a).isEmpty());assertFalse(knowledge.privateContext(sid,a).contains("ITEM_PLACEMENT"));
  checkpoints.restore(sid,0);assertEquals("",placements.position(sid,a,ref));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
 }
 @Test void placementDeliverySurvivesHistoryBudgetAndLoanDoesNotTransferOwnership()throws Exception{
  String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var self=json.readTree(messages.get(1).get("content"));return response(a.equals(self.path("id").asText())?choice("整理工具","","","把短刀放到膝上"):choice("看看现场","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("你结算"))return response(result("工匠甲把短刀放到膝上。",2,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","","after","膝上"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("placement-chain")).get("status"));
  assertTrue(knownState.current(sid,player,scene).stream().anyMatch(f->ref.equals(f.get("subject")) && "膝上".equals(f.get("value"))));
  assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
  for(int i=0;i<40;i++)delivered(a,"budget:"+i,Map.of("recordType","UTTERANCE","text","普通旧话", "endSeconds",3));
  assertTrue(knownState.current(sid,a,scene).stream().anyMatch(f->"膝上".equals(f.get("value"))));
  rolePrompts.clear();doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);rolePrompts.add(messages.get(1).get("content"));return response(choice("我自己决定借出","","","交给玩家"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("你结算"))return response(result("工匠甲把短刀交给玩家暂用。",2,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var rt=jdbc.queryForMap("SELECT * FROM game_runtime WHERE session_id=?",sid);
  var next=new ConversationOperationService.Request("loan-chain",sid,pid,scene,"请把短刀借给我。",new ActionRequest.DialogueInput(a,"请把短刀借给我。"),((Number)rt.get("branch_epoch")).longValue(),((Number)rt.get("revision")).longValue());
  assertEquals("COMPLETED",operations.create(next).get("status"));
  var borrowed=itemState.owned(sid,actors.findById(player).orElseThrow()).get(0);assertEquals(a,ItemStateService.legalOwner(borrowed));assertEquals(ref,borrowed.ref());
  assertEquals("",placements.position(sid,player,ref));
  assertTrue(rolePrompts.stream().map(this::decodePrompt).filter(n->a.equals(n.path("id").asText())).anyMatch(n->n.path("currentKnownState").toString().contains("膝上") && n.path("ownItemPlacements").toString().contains("膝上")));
 }
 @Test void overdueUncommittedProcessCannotPlaceAnEventInThePast()throws Exception{
  jdbc.update("INSERT IGNORE INTO game_runtime(session_id) VALUES(?)",sid);
  assertEquals(1,jdbc.update("UPDATE game_runtime SET story_seconds=50 WHERE session_id=?",sid));
  var record=new LinkedHashMap<String,Object>();record.put("decision",choice("已有决定","","","整理衣物"));
  record.put("execution",result("尚未提交的旧结果",4,List.of()));record.put("timing",Map.of("start",0,"executionStart",0,"sceneId",scene));
  record.put("spoken",true);record.put("executed",false);record.put("background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'test:overdue','旧进行中工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(record),scene);
  var actual=operations.create(new ConversationOperationService.Request("overdue",sid,pid,scene,"检查绳结",null,0L,0L));
  assertEquals("COMPLETED",actual.get("status"));assertTrue(((Number)actual.get("storySeconds")).longValue()>=50);
  var saved=json.readTree(payload("overdue"));for(var event:saved.path("events"))assertTrue(event.path("endSeconds").asLong()>=50);
 }
 @Test void directedRequestSurvivesBusyProcessAndIsConsumedByFreshDecision()throws Exception{
  var record=new LinkedHashMap<String,Object>();record.put("decision",choice("已有工作","","","整理衣物"));
  record.put("execution",result("工匠甲完成整理。",20,List.of()));record.put("timing",Map.of("start",0,"executionStart",0,"sceneId",scene));
  record.put("spoken",true);record.put("executed",false);record.put("background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'test:busy','工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(record),scene);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var self=json.readTree(messages.get(1).get("content"));
   if(a.equals(self.path("id").asText())){
    assertTrue(self.path("pendingStimuli").toString().contains("能借我短刀吗"));
    return response(choice("我已听到，但不愿借出","不借。",player,""));
   }
   return response(choice("继续观察","","",""));
  }).when(model).chatSubForJson(anyList());
  var first=operations.create(talk("busy-mail"));assertEquals("COMPLETED",first.get("status"));assertTrue(story(first).contains("不借。"));
  assertTrue(((Number)first.get("storySeconds")).longValue()<20,"speech must be possible while body work continues");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INBOX'",Integer.class,sid,a));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
   if(prompt.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待25秒","seconds",25))));
   if(prompt.contains("你结算"))return response(result("整理完成。",4,List.of()));
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var rt=jdbc.queryForMap("SELECT * FROM game_runtime WHERE session_id=?",sid);
  var actual=operations.create(new ConversationOperationService.Request("listen-busy",sid,pid,scene,"等待25秒",null,((Number)rt.get("branch_epoch")).longValue(),((Number)rt.get("revision")).longValue()));
  assertEquals("COMPLETED",actual.get("status"));assertFalse(story(actual).contains("不借。"),"old reply must not replay while finishing work");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INBOX'",Integer.class,sid,a));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_CONSUMPTION'",Integer.class,sid,a));
 }
 @Test void heardEvidenceIsOnlyDeduplicatedAgainstActuallyIncludedSpeech()throws Exception{
  String quote="锯留在原处，不能带走";
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'test:heard','借用条件',?,'HEARD',?,?,'',0)",sid,a,quote,b,scene);
  assertTrue(knowledge.privateContext(sid,a,true).contains(quote));
  assertTrue(knowledge.privateContext(sid,a,Set.of(new KnowledgeService.IncludedSpeech(player,quote))).contains(quote));
  assertFalse(knowledge.privateContext(sid,a,Set.of(new KnowledgeService.IncludedSpeech(b,quote))).contains(quote));
  for(int i=0;i<30;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'新动作','{}','TURN_EVENT','',?,'',0)",sid,a,"new:"+i,scene);
  var actual=operations.create(talk("evidence-budget"));assertEquals("COMPLETED",actual.get("status"));
  assertTrue(rolePrompts.stream().map(this::decodePrompt).filter(n->a.equals(n.path("id").asText())).anyMatch(n->n.path("ownKnowledge").asText().contains(quote)));
 }
 JsonNode decodePrompt(String prompt){try{return json.readTree(prompt);}catch(Exception e){throw new IllegalStateException(e);}}
 @Test void schedulingRecordsCannotCrowdOutEvidenceOrBeShared()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'test:evidence','亲耳消息','锯留在原处','HEARD','',?,'',0)",sid,a,scene);
  long internalId=0;
  for(int i=0;i<12;i++){
   String type=List.of("ROLE_INBOX","ROLE_CONSUMED","ROLE_CONSUMPTION").get(i%3);
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'内部调度','技术记录',?,'',?,'',0)",sid,a,"internal:"+i,type,scene);
   internalId=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",Long.class,sid,a,"internal:"+i);
   String result=knowledge.share(sid,a,b,internalId,"告诉对方内部调度",scenes.findById(scene).orElseThrow(),Map.of(a,actors.findById(a).orElseThrow(),b,actors.findById(b).orElseThrow()),0);
   assertTrue(result.contains("内部角色状态"));
  }
  assertEquals(1,knowledge.list(sid,a).size());
  assertTrue(knowledge.privateContext(sid,a).contains("锯留在原处"));
  assertFalse(knowledge.privateContext(sid,a).contains("技术记录"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TOLD'",Integer.class,sid,b));
 }
 @Test void consumptionKeyFitsDatabaseWithRealLengthEventReferences()throws Exception{
  String longRef="inbox:"+"x".repeat(84);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'长消息引用','{}','ROLE_INBOX','',?,'',0)",sid,a,longRef,scene);
  var actual=operations.create(talk(UUID.randomUUID().toString()));assertEquals("COMPLETED",actual.get("status"));
  var consumed=jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_CONSUMPTION'",sid,a);
  assertEquals(2,consumed.size());for(var row:consumed)assertTrue(row.get("source_ref").toString().length()<=64);
  assertTrue(consumed.stream().anyMatch(row->row.get("content").toString().contains(longRef)));
 }
 @Test void newNpcWorkAfterAnOutcomeIsSavedWithoutAnEndlessChain()throws Exception{
  doAnswer(call->{var answer=choice("继续自己的事务","","","整理衣物");answer.put("post_action_intent","完成后考虑下一件事");return response(answer);}).when(model).chatSubForJson(anyList());
  var actual=operations.create(new ConversationOperationService.Request("outcome-boundary",sid,pid,scene,"检查绳结",null,0L,0L));
  assertEquals("COMPLETED",actual.get("status"));
  assertEquals(8L,((Number)actual.get("storySeconds")).longValue());
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid),"future choices stay unexecuted until a later player horizon");
 }
 @Test void npcAddressingPlayerCannotGenerateAnUncommandedPlayerChoice()throws Exception{
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var self=json.readTree(messages.get(1).get("content"));
   assertNotEquals(player,self.path("id").asText(),"human player must never enter NPC decision model");
   return response(choice("自己考虑", "请你过来",player,""));
  }).when(model).chatSubForJson(anyList());
  var actual=operations.create(talk("human-control"));
  assertEquals("COMPLETED",actual.get("status"));
  var saved=json.readTree(payload("human-control"));
  for(var job:saved.path("queue"))assertFalse(job.asText().startsWith(player+"|reply"));
 }
 @Test void billingSurvivesPartialRecoveryAndReadsDoNotAccumulate()throws Exception{
  var count=new AtomicInteger();doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(!a.equals(json.readTree(messages.get(1).get("content")).path("id").asText()))return response(choice("","","",""));try(var billed=LLMUsage.startCall("test-sub")){billed.response(new LLMResponse("",null,100,20,120,0.012),true);if(count.getAndIncrement()==0)throw new LLMApiException("invalid generated answer",false);return response(choice("","","",""));}}).when(model).chatSubForJson(anyList());
  var first=operations.create(talk("bill-recover"));assertEquals("PARTIAL",first.get("status"));assertEquals(1,((Number)((Map<?,?>)first.get("usage")).get("calls")).intValue());
  var done=operations.recover(sid,pid,"bill-recover",((Number)first.get("branchEpoch")).longValue());assertEquals("COMPLETED",done.get("status"));
  var observed=(Map<?,?>)done.get("usage");assertEquals(2,((Number)observed.get("calls")).intValue());assertEquals(240L,((Number)observed.get("total")).longValue());
  assertEquals(observed,operations.read(sid,pid,"bill-recover",null).get("usage"));assertEquals(observed,operations.create(talk("bill-recover")).get("usage"));
  var history=(List<Map<String,Object>>)transcript.read(sid,pid,null).get("turns");assertEquals(observed,history.get(history.size()-1).get("usage"));assertEquals(240,jdbc.queryForObject("SELECT token_total FROM interaction_log WHERE session_id=?",Integer.class,sid));
 }
 @Test void cancelledLateResponseRecordsCostWithoutRevivingWorld()throws Exception{
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(!a.equals(json.readTree(messages.get(1).get("content")).path("id").asText()))return response(choice("","","",""));try(var billed=LLMUsage.startCall("late")){started.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));billed.response(new LLMResponse("",null,123,7,130,0.02),true);return response(choice("secret","迟到话不能发生",player,""));}}).when(model).chatSubForJson(anyList());
  var pool=Executors.newSingleThreadExecutor();try{
   var pending=pool.submit(()->operations.create(talk("bill-cancel")));assertTrue(started.await(10,TimeUnit.SECONDS));var rt=jdbc.queryForMap("SELECT * FROM game_runtime WHERE session_id=?",sid);operations.cancel(sid,pid,"bill-cancel",((Number)rt.get("branch_epoch")).longValue());release.countDown();var rejected=assertThrows(ExecutionException.class,()->pending.get(15,TimeUnit.SECONDS));assertInstanceOf(com.bs.narrative.exception.BusinessException.class,rejected.getCause());
   var read=operations.read(sid,pid,"bill-cancel",null);assertEquals("CANCELLED",read.get("status"));assertFalse(story(read).contains("迟到话"));assertEquals(130L,((Number)((Map<?,?>)read.get("usage")).get("total")).longValue());
   checkpoints.restore(sid,0);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM llm_operation_usage WHERE session_id=?",Integer.class,sid));
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void parallelUnconfirmedCallsAndDuplicateReportsHaveStableTotals()throws Exception{
  var chapter=operations.create(talk("billing-only"));long log=((Number)chapter.get("logId")).longValue();var sink=usage.sink(sid,0,"billing-only",log);var start=new LLMUsage.CallEvent(UUID.randomUUID().toString(),"test","STARTED",null,false);sink.accept(start);sink.accept(start);
  var response=new LLMUsage.CallEvent(start.id(),"test","RESPONSE",new LLMResponse("",null,10,2,12,0.001),true);sink.accept(response);sink.accept(response);
  var executor=Executors.newFixedThreadPool(2);try{var tasks=new ArrayList<Future<?>>();for(int n=0;n<2;n++)tasks.add(executor.submit((Callable<Void>)()->{try(var scope=LLMUsage.observe(sink);var c=LLMUsage.startCall("test")){}return null;}));for(var task:tasks)task.get();}finally{executor.shutdownNow();}
  var totals=usage.forOperation(sid,0,"billing-only");assertEquals(3L,((Number)totals.get("calls")).longValue());assertEquals(12L,((Number)totals.get("total")).longValue());assertEquals(2L,((Number)totals.get("unconfirmedCalls")).longValue());assertEquals(0L,((Number)usage.forOperation(sid,1,"billing-only").get("calls")).longValue());
 }
 @Test void parallelBillingReservationsCannotExceedTheSameCallCeiling()throws Exception{
  var chapter=operations.create(talk("bounded-billing"));long log=((Number)chapter.get("logId")).longValue();var sink=usage.sink(sid,0,"bounded-billing",log,2);
  var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);var allowed=new AtomicInteger();var denied=new AtomicInteger();
  try{var futures=new ArrayList<Future<?>>();for(int n=0;n<6;n++)futures.add(pool.submit(()->{try{start.await();try(var observer=LLMUsage.observe(sink);var call=LLMUsage.startCall("bounded-test")){allowed.incrementAndGet();call.response(new LLMResponse("",null,10,2,12,0.001),true);}}catch(LLMApiException e){assertEquals("MODEL_CALL_LIMIT",e.category());denied.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));start.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);}
  finally{start.countDown();pool.shutdownNow();}
  assertEquals(2,allowed.get());assertEquals(4,denied.get());assertEquals(2L,((Number)usage.forOperation(sid,0,"bounded-billing").get("calls")).longValue());
  String callId=jdbc.queryForList("SELECT call_id FROM llm_operation_usage WHERE session_id=?",String.class,sid).get(0);sink.accept(new LLMUsage.CallEvent(callId,"bounded-test","STARTED",null,false));assertEquals(2L,((Number)usage.forOperation(sid,0,"bounded-billing").get("calls")).longValue());
 }
 void billedSingleAction(AtomicInteger paid)throws Exception{
  doAnswer(call->{try(var billing=LLMUsage.startCall("bounded-action")){paid.incrementAndGet();billing.response(new LLMResponse("",null,10,2,12,0.001),true);
   List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","观察石柱"))));
   return response(result("你观察了石柱。",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));}
  }).when(model).chatForJson(anyList());
 }
 @Test void callLimitPausesWithoutChargingDeniedCallsAndOnlyExplicitRecoveryAddsAllowance()throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",1);var paid=new AtomicInteger();billedSingleAction(paid);
  try{var first=operations.create(nextInput("call-limit","观察石柱"));assertEquals("PARTIAL",first.get("status"));assertEquals("MODEL_CALL_LIMIT",first.get("pendingKind"));assertEquals(1,paid.get());assertEquals(0L,first.get("storySeconds"));assertEquals(1L,json.readTree(payload("call-limit")).path("modelCallCeiling").asLong());
   operations.read(sid,pid,"call-limit",0L);operations.create(nextInput("call-limit","观察石柱"));assertEquals(1,paid.get(),"read and duplicate submission must not grant requests");
   var done=operations.recover(sid,pid,"call-limit",0);assertEquals("COMPLETED",done.get("status"));assertEquals(2,paid.get());assertEquals(2L,json.readTree(payload("call-limit")).path("modelCallCeiling").asLong());assertEquals(1L,done.get("storySeconds"));assertEquals(2L,((Number)((Map<?,?>)done.get("usage")).get("calls")).longValue());assertEquals(1,json.readTree(payload("call-limit")).path("events").findValues("text").stream().filter(t->t.asText().contains("观察了石柱")).count());
  }finally{org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",24);}
 }
 @Test void automaticSlicesDoNotResetThePaidCallCeiling()throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",1);org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",1);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",8);var paid=new AtomicInteger();billedSingleAction(paid);
  try{operations.submit(nextInput("auto-call-limit","观察石柱"));var stopped=awaitAsync("auto-call-limit");assertEquals("PARTIAL",stopped.get("status"));assertEquals("MODEL_CALL_LIMIT",stopped.get("pendingKind"));assertEquals(1,paid.get());assertEquals(1L,json.readTree(payload("auto-call-limit")).path("modelCallCeiling").asLong());}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",24);org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",64);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",4);}
 }
 @Test void incomingSpeechIsIncludedOnceWithoutErasingItsDurableHistory()throws Exception{
  var checked=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var facts=json.readTree(messages.get(1).get("content"));if(a.equals(facts.path("id").asText())){
    for(var mail:facts.path("pendingStimuli")){var incoming=json.readTree(mail.path("content").asText());String eventId=incoming.path("eventId").asText();if(eventId.isBlank())continue;
     for(var previous:facts.path("ownRecords"))if("TURN_EVENT".equals(previous.path("source_type").asText()))assertNotEquals(incoming,json.readTree(previous.path("content").asText()),"the exact pending delivery should not be copied into old records");checked.incrementAndGet();}
   }return response(choice("已听见，暂不回应","","",""));
  }).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("compact-inbox")).get("status"));assertTrue(checked.get()>0);assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT'",Integer.class,sid,a)>0);assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_CONSUMPTION'",Integer.class,sid,a)>0);
 }
 ConversationOperationService.Request storyRequest(String id,String text){var r=nextInput(id,text);return new ConversationOperationService.Request(r.operationId(),r.sessionId(),r.playerId(),r.sceneId(),r.playerText(),null,r.branchEpoch(),r.expectedRevision(),true);}
 void capturedRoutineAwaitHeard(boolean reply)throws Exception{
  schedule(a,reply?3600:0);schedule(b,3600);var packet=json.readTree(getClass().getResourceAsStream("/fixtures/await-heard-authorization.json"));for(var unit:packet.path("units")){if(unit.path("targetId").asText().startsWith("npc_"))((com.fasterxml.jackson.databind.node.ObjectNode)unit).put("targetId",a);if(unit.path("condition").isObject())((com.fasterxml.jackson.databind.node.ObjectNode)unit.path("condition")).put("actorId",a);}
  var plans=new AtomicInteger();var moves=new AtomicInteger();var roles=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));int n=roles.getAndIncrement();var d=choice("自己的判断",reply?"看地面被脚印压紧的地方。":n==0?"先前的一句话。":"",player,"");d.put("next_think_seconds",3600);return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(packet);
   if(system.contains("连贯故事节奏规划"))return response(plans.getAndIncrement()==0?Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text","踩实的土路该怎样辨认？"):Map.of("kind","DONE","reason","这一段话题已结束。"));
   moves.incrementAndGet();var facts=json.readTree(m.get(1).get("content"));return response(result("你退到空地东侧一步。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","空地东侧一步")))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("captured-await-heard",packet.path("rawText").asText()));assertEquals("COMPLETED",done.get("status"));assertEquals(reply?1:0,moves.get());
  JsonNode heard=json.createObjectNode();for(var outcome:json.readTree(payload("captured-await-heard")).path("waitResults"))if("u3".equals(outcome.path("unitId").asText()))heard=outcome;assertEquals(reply?"SATISFIED":"BOUND_REACHED",heard.path("status").asText());
  if(reply){assertEquals("空地东侧一步",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));assertTrue(((Number)done.get("storySeconds")).longValue()<30,"hearing the answer must not add another 30-second wait");}
 }
 @Test void capturedRoutineReplyAlreadyHeardAllowsTheExplicitNextMove()throws Exception{capturedRoutineAwaitHeard(true);}
 @Test void anOlderSpeechBeforeTheRoutineQuestionDoesNotSatisfyListening()throws Exception{capturedRoutineAwaitHeard(false);}
 @Test void aRoutineTopicAlreadyBeingDoneDoesNotDiscardTheNextExplicitAction()throws Exception{
  schedule(a,3600);schedule(b,3600);String topic="我向工匠甲了解路况，",later="然后退到石柱后。";var worlds=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",topic,"routineConversation",true,"targetId",a),Map.of("type","ACTION","text",later,"localMovement",true))));
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","你已经掌握了足够的路况信息。"));
   worlds.incrementAndGet();assertEquals(later,facts.path("attempt").asText());return response(result("你退到了石柱后。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","石柱后")))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("done-topic-next-action",topic+later));assertEquals("COMPLETED",done.get("status"));assertEquals(1,worlds.get());assertEquals("石柱后",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
 }
 @Test void aFutureConditionalAttackDoesNotAuthorizeAnNpcToHitThePlayerBeforeTheCondition()throws Exception{
  schedule(a,0);schedule(b,3600);String waiting="等工匠甲说话后，",attack="我再与工匠甲交手。";var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text",waiting,"seconds",10,"condition",Map.of("type","HEARD_SPEECH","text","工匠甲说话","actorId",a)),Map.of("type","ACTION","text",attack,"whenWaitSatisfied",true,"conditionText","工匠甲说话","contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU")))));
   worlds.incrementAndGet();var contest=json.readTree(m.get(1).get("content")).path("serverContest");return response(result("甲与玩家实际交手。",1,List.of(Map.of("kind","CONTEST","targetId",player,"outcome",contest.path("outcome").asText(),"fingerprint",contest.path("fingerprint").asText()))));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{var decision=choice("决定动手","",player,"向玩家挥拳");decision.put("contest",Map.of("kind","ATTACK","targetId",player,"statKey","DOU"));return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("conditional-attack-is-not-current",waiting+attack));assertEquals("COMPLETED",done.get("status"));assertEquals(0,worlds.get());assertEquals(100,((Map<?,?>)done.get("scene")).get("playerHealth"));assertEquals("HOSTILITY",((Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress")).get("decisionType"));
 }
 @Test void aFinishedPlayerContestCannotAuthorizeAnotherAutomaticNpcStrike()throws Exception{
  var p=json.createObjectNode().put("playerRole",player).put("storyMode",true).put("sceneId",scene).put("operationId","old-strike").put("endSeconds",0);String old=player+"|authorized_0",fresh=a+"|new-strike";
  p.putArray("queue").add(old).add(fresh);p.putArray("processed").add(old);p.putArray("executedJobs").add(old);p.putArray("events");p.putArray("backgroundJobs");p.putObject("jobTimes").set(fresh,json.createObjectNode().put("sceneId",scene).put("start",0));
  var original=choice("","","","交手");original.put("contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU"));var counter=choice("","",player,"再次向玩家挥拳");counter.put("contest",Map.of("kind","ATTACK","targetId",player,"statKey","DOU"));p.putObject("decisions").set(old,json.valueToTree(original));p.withObject("decisions").set(fresh,json.valueToTree(counter));p.putObject("authorization").putArray("steps").add(json.valueToTree(Map.of("type","ACTION","text","交手","contest",Map.of("targetId",a))));
  var begin=ConversationOperationService.class.getDeclaredMethod("beginContests",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,List.class);begin.setAccessible(true);begin.invoke(operations,sid,p,List.of(fresh));assertEquals("HOSTILITY",p.path("storyStop").path("decisionType").asText());
 }
 @Test void arrivingStillStopsBeforeUsingTheOldSceneButReportsRemainingInstructions()throws Exception{
  schedule(a,3600);schedule(b,3600);String destination=scene+"_east";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'东侧廊下','[]','廊下无人。',1,0)",destination,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','东侧出口')",sid,scene,destination);
  String move="我走到东侧廊下，",later="再查看廊下。";var worlds=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",move),Map.of("type","ACTION","text",later))));worlds.incrementAndGet();return response(result("你沿出口抵达东侧廊下。",1,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",destination))));}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("arrival-with-remaining-step",move+later));assertEquals("COMPLETED",done.get("status"));assertEquals(1,worlds.get());assertEquals(destination,((Map<?,?>)done.get("scene")).get("sceneId"));assertTrue(((Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress")).get("reason").toString().contains("后续行动尚未执行，共1项"));
 }
 @Test void aRealImportantProposalStillInterruptsAnExplicitOrdinaryFollowUp()throws Exception{
  schedule(b,3600);String first="我查看工匠甲，",later="然后查看石柱。";var worlds=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",first),Map.of("type","ACTION","text",later))));worlds.incrementAndGet();return response(result("你查看工匠甲。",1,List.of()));}).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var decision=a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("自己的立场","与我结盟吗？",player,""):choice("","","","");if(!decision.get("speech").toString().isBlank())decision.put("player_decision",Map.of("type","ALLIANCE","question","你是否同意？"));return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("choice-before-follow-up",first+later));assertEquals("COMPLETED",done.get("status"));assertEquals(1,worlds.get());assertEquals(true,((Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress")).get("requiresInput"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BLOCKED","TRANSFER","CONTEST"})
 void explicitFollowUpStillRunsAfterAnOrdinaryResultWithoutBeingDropped(String kind)throws Exception{
  schedule(a,3600);schedule(b,3600);String first=switch(kind){case "TRANSFER"->"我把布条交给工匠甲，";case "CONTEST"->"我与工匠甲交手，";default->"我尝试解开绳结，";};String last="然后退到石柱后。";var executions=new AtomicInteger();
  jdbc.update("UPDATE god_profile SET inventory=? WHERE god_id=?",json.writeValueAsString(List.of("布条")),player);String ref=itemState.owned(sid,actors.findById(player).orElseThrow()).get(0).ref();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析")){var step=new LinkedHashMap<String,Object>(Map.of("type","ACTION","text",first));if("CONTEST".equals(kind))step.put("contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU"));return response(Map.of("steps",List.of(step,Map.of("type","ACTION","text",last,"localMovement",true))));}
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","已按你的安排退开。"));
   executions.incrementAndGet();if(last.equals(facts.path("attempt").asText()))return response(result("你按原先的安排退到石柱后。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","石柱后")))));
   if("TRANSFER".equals(kind))return response(result("你把自己的布条交给工匠甲。",1,List.of(Map.of("kind","TRANSFER","fromId",player,"toId",a,"itemRef",ref,"mode","GIFT"))));
   if("CONTEST".equals(kind)){var server=facts.path("serverContest");return response(result("你与工匠甲完成了这一轮交手。",1,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",server.path("outcome").asText(),"fingerprint",server.path("fingerprint").asText()))));}
   var blocked=new LinkedHashMap<>(result("绳结太紧，你未能解开。",1,List.of()));blocked.put("status","BLOCKED");return response(blocked);
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("ordinary-follow-up-"+kind,first+last));assertEquals("COMPLETED",done.get("status"));assertEquals(2,executions.get());assertTrue(story(done).contains("按原先的安排退到"));assertEquals("石柱后",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
 }
 @Test void plannerChoiceUsesActualDeliveredSpeechAndItsActorInsteadOfInventedQuestionDetails()throws Exception{
  schedule(b,3600);String text="我查看眼前情况。",quote="你愿意与我结盟吗？";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
   if(system.contains("连贯故事节奏规划")){String evidence="";for(var event:facts.path("visibleEvents"))if(quote.equals(event.path("speech").asText()))evidence=event.path("id").asText();assertFalse(evidence.isBlank());return response(Map.of("kind","DECISION","decisionType","ALLIANCE","question","INVENTED_CONDITION：交出全部物品后才能结盟。","evidenceEventId",evidence,"counterpartId",b));}
   return response(result("你观察了眼前人物。",1,List.of()));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("自己的意愿",quote,player,""):choice("","","",""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("grounded-planner-choice",text));assertEquals("COMPLETED",done.get("status"));var progress=(Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress");assertEquals(true,progress.get("requiresInput"));assertEquals(a,progress.get("counterpartId"));assertTrue(progress.get("reason").toString().contains(quote));assertFalse(done.toString().contains("INVENTED_CONDITION"));assertEquals(List.of(),actors.findById(player).orElseThrow().getInventory());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"null","[]","\"legacy\""})
 void nonObjectLegacyChapterMetadataCannotBlockTheNextPlayerInput(String snapshot)throws Exception{
  schedule(a,3600);schedule(b,3600);jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,0,'旧输入','TALK','NO_OP','narrator：旧正文',?)",sid,pid,scene,snapshot);
  assertEquals(scene,game.describeScene(pid,sid).get("sceneId"));
  String input="我查看石柱。";doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析")){var facts=json.readTree(m.get(1).get("content"));assertFalse(facts.has("decisionContext"));return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input))));}if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","查看完毕。"));return response(result("你看过石柱。",1,List.of()));}).when(model).chatForJson(anyList());
  var next=operations.create(storyRequest("legacy-metadata-input",input));assertEquals("COMPLETED",next.get("status"));assertTrue(story(next).contains("看过石柱"));
 }
 @Test void currentSceneUsesTheLatestArrivalChapterInsteadOfThePreviousVisit()throws Exception{
  String remote=scene+"_other";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'山坳','[]','山坳无人。',1,0)",remote,sid);
  String insert="INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot,created_at) VALUES(?,?,?,?,'移动','TALK','NO_OP','narrator：经过',?,'2026-01-01 00:00:00')";
  jdbc.update(insert,sid,pid,scene,1,json.writeValueAsString(Map.of("eventTimeline",List.of(Map.of("kind","ACTION_RESULT","text","旧的离开记录")),"decisionPoints",List.of(Map.of("question","旧的结盟提议")))));
  jdbc.update(insert,sid,pid,remote,2,json.writeValueAsString(Map.of("eventTimeline",List.of(Map.of("kind","ACTION_RESULT","text","你已原路返回石柱旁。")),"decisionPoints",List.of())));
  var current=game.describeScene(pid,sid);assertEquals(scene,current.get("sceneId"));assertEquals("你已原路返回石柱旁。",((Map<?,?>)((List<?>)current.get("eventTimeline")).get(0)).get("text"));assertTrue(((List<?>)current.get("decisionPoints")).isEmpty());
 }
 @Test @org.springframework.transaction.annotation.Transactional void sceneMetadataSortsLogKeysInsteadOfLargeJsonAndUsesTheLatestTie()throws Exception{
  long original=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);try{jdbc.execute("SET SESSION sort_buffer_size=32768");for(int i=0;i<30;i++)jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot,created_at) VALUES(?,?,?,?,'查看','TALK','NO_OP','narrator：查看',?,'2026-01-01 00:00:00')",sid,pid,scene,i,json.writeValueAsString(Map.of("padding","x".repeat(50000),"eventTimeline",List.of(Map.of("kind","ACTION_RESULT","text","history_"+i)))));
   var current=game.describeScene(pid,sid);assertEquals("history_29",((Map<?,?>)((List<?>)current.get("eventTimeline")).get(0)).get("text"));
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+original);}
 }
 @Test void explicitContinueUsesSavedPlayerProgressAcrossChaptersWithoutRestarting()throws Exception{
  schedule(a,3600);schedule(b,3600);String original="我沿石柱逐段查看刻痕，不碰物品。",follow="继续刚才的查看，仍不碰物品。";var executions=new AtomicInteger();int[] remaining={9,8,7,6,3,0};
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析")){
    String input=facts.path("input").asText();var step=new LinkedHashMap<String,Object>(Map.of("type","ACTION","text",input));
    if(follow.equals(input)){assertEquals(original,facts.path("pendingPlayerWork").path("attempt").asText());assertEquals(6,facts.path("pendingPlayerWork").path("remainingSeconds").asInt());step.put("resumeWorkRef",facts.path("pendingPlayerWork").path("ref").asText());}
    return response(Map.of("steps",List.of(step)));
   }
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","本次查看已结束。"));
   assertEquals(original,facts.path("attempt").asText());int n=executions.getAndIncrement();if(n>0)assertEquals(remaining[n-1],facts.path("acceptedWork").path("actual").path("remainingSeconds").asInt());if(n>=4)assertTrue(facts.path("continuationInstructions").toString().contains(follow));
   var result=new LinkedHashMap<>(result(n==0?"你第一次查看外层刻痕。":n==5?"你沿原进度看完了最后一段刻痕。":"你沿原进度查看下一段刻痕。",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));if(remaining[n]>0){result.put("status","ONGOING");result.put("remainingSeconds",remaining[n]);}return response(result);
  }).when(model).chatForJson(anyList());
  var first=operations.create(storyRequest("long-player-work",original));assertEquals("COMPLETED",first.get("status"));assertEquals(4,executions.get());
  var next=operations.create(storyRequest("resume-player-work",follow));assertEquals("COMPLETED",next.get("status"));assertEquals(6,executions.get());assertFalse(story(next).contains("第一次查看"));assertTrue(story(next).contains("最后一段"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='v2:work'",Integer.class,sid,player));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void invalidOrSupersededPlayerWorkReferenceCannotExecuteTheOldAttempt(boolean superseded)throws Exception{
  schedule(a,3600);schedule(b,3600);String old="查看原来的刻痕，不碰物品。";var actual=new LinkedHashMap<>(result("之前查看了一部分。",1,List.of()));actual.put("status","ONGOING");actual.put("remainingSeconds",5);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'v2:work','旧工作',?,'ROLE_TASK','',?,'',0)",sid,player,json.writeValueAsString(Map.of("attempt",old,"actual",actual,"lastEndSeconds",0)),scene);
  String first="先查看衣袖，",last="然后继续检查刻痕。",text=superseded?first+last:last;var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析")){var steps=new ArrayList<Map<String,Object>>();if(superseded)steps.add(Map.of("type","ACTION","text",first));steps.add(Map.of("type","ACTION","text",last,"resumeWorkRef",superseded?facts.path("pendingPlayerWork").path("ref").asText():"wrong-reference"));return response(Map.of("steps",steps));}
   worlds.incrementAndGet();assertEquals(first,facts.path("attempt").asText());return response(result("你查看了衣袖。",1,List.of()));
  }).when(model).chatForJson(anyList());
  var stopped=operations.create(storyRequest("invalid-resume-player",text));assertEquals("PARTIAL",stopped.get("status"));assertEquals(superseded?1:0,worlds.get());assertFalse(story(stopped).contains("原来的刻痕"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void resumedMovementStillRequiresARealPositionEffectForNewAndLegacyWork(boolean legacy)throws Exception{
  schedule(a,3600);schedule(b,3600);String old="沿石柱走近门边，不触碰旁人。";var actual=new LinkedHashMap<>(result("你已向前走到石柱边，尚未走到门边。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",""),"after",Map.of("position","石柱边")))));actual.put("status","ONGOING");actual.put("remainingSeconds",5);
  var work=new LinkedHashMap<String,Object>(Map.of("attempt",old,"actual",actual,"lastEndSeconds",0));if(!legacy){var decision=choice("","","",old);decision.put("localMovement",true);work.put("decision",decision);}
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'v2:work','旧工作',?,'ROLE_TASK','',?,'',0)",sid,player,json.writeValueAsString(work),scene);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','身体',?,'BODY_STATE','',?,'',0)",sid,player,json.writeValueAsString(Map.of("awake",true,"bound",false,"position","石柱边","activity","")),scene);
  var worlds=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","继续向前。","resumeWorkRef",facts.path("pendingPlayerWork").path("ref").asText()))));
   assertTrue(facts.path("selectedLocalMovement").asBoolean());worlds.incrementAndGet();return response(result("你已经抵达门边。",1,List.of()));
  }).when(model).chatForJson(anyList());
  var stopped=operations.create(storyRequest("resume-needs-position","继续向前。"));assertEquals("PARTIAL",stopped.get("status"));assertEquals(2,worlds.get());assertFalse(story(stopped).contains("已经抵达"));assertEquals("石柱边",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
 }
 @Test void backgroundPlanAtWaitBoundaryWaitsForTheNextPlayerOperationWithoutAPaidExecution()throws Exception{
  acceptedWork(2,List.of());schedule(a,3600);schedule(b,3600);var worlds=new AtomicInteger();
  var plan=Map.of("decision",choice("整理完再检查","","","检查绳结"),"spoken",false,"chosenAtSeconds",0);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'test:next-plan','后续工作',?,'ROLE_PLAN','',?,'',0)",sid,a,json.writeValueAsString(plan),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析")){String text=json.readTree(m.get(1).get("content")).path("input").asText();return response(Map.of("steps",List.of(Map.of("type","WAIT","text",text,"seconds",text.contains("2")?2:5))));}
   worlds.incrementAndGet();return response(result("甲实际完成了后续检查。",1,List.of()));
  }).when(model).chatForJson(anyList());
  var first=operations.create(nextInput("boundary-plan","等待2秒"));assertEquals("COMPLETED",first.get("status"));assertEquals(2L,first.get("storySeconds"));assertEquals(0,worlds.get(),"the next action was only selected at this operation's final boundary");
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
  var next=operations.create(nextInput("next-plan","等待5秒"));assertEquals("COMPLETED",next.get("status"));assertEquals(1,worlds.get());assertTrue(story(next).contains("实际完成了后续检查"));assertFalse(story(first).contains("实际完成了后续检查"));
 }
 @Test void actionResultReactionAtTheChapterBoundarySurvivesUntilTheNextWait()throws Exception{
  schedule(a,3600);schedule(b,3600);var decision=choice("自己的检查","","","检查绳结");decision.put("post_action_intent","检查完成后向玩家报告结果");
  var work=Map.of("decision",decision,"execution",result("甲的检查实际完成。",2,List.of()),"timing",Map.of("start",0,"sceneId",scene),"spoken",true,"executed",false,"background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'test:report-at-end','旧工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(work),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));String text=facts.path("input").asText();return response(Map.of("steps",List.of(Map.of("type","WAIT","text",text,"seconds",text.contains("2")?2:5))));}).when(model).chatForJson(anyList());
  var choices=new AtomicInteger();doAnswer(call->{choices.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));assertEquals("OWN_ACTION_RESULT",facts.path("decisionTrigger").asText());assertTrue(facts.path("pendingStimuli").toString().contains("甲的检查实际完成"));return response(choice("知道检查结果了","检查好了。",player,""));}).when(model).chatSubForJson(anyList());
  var first=operations.create(nextInput("report-boundary","等待2秒"));assertEquals("COMPLETED",first.get("status"));assertEquals(0,choices.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INBOX'",Integer.class,sid,a));
  var next=operations.create(nextInput("hear-report-next","等待5秒"));assertEquals("COMPLETED",next.get("status"));assertEquals(1,choices.get());assertTrue(story(next).contains("检查好了"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INBOX'",Integer.class,sid,a));
 }
 @Test void carriedProposalKeepsItsSpokenStateWhenAnImportantChoiceStopsTheChapter()throws Exception{
  schedule(a,3600);schedule(b,3600);var decision=choice("PRIVATE_PROPOSAL_INTENT","与我结盟吗？",player,"整理衣物");decision.put("player_decision",Map.of("type","ALLIANCE","question","PRIVATE_PROPOSAL_QUESTION"));
  var work=Map.of("decision",decision,"execution",result("甲完成了自己的整理。",20,List.of()),"timing",Map.of("start",0,"sceneId",scene),"spoken",false,"executed",false,"background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'test:carried-proposal','旧工作',?,'ROLE_PROCESS','',?,'',0)",sid,a,json.writeValueAsString(work),scene);
  doAnswer(call->response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待10秒","seconds",10))))).when(model).chatForJson(anyList());
  var first=operations.create(storyRequest("carried-proposal","等待10秒"));assertEquals("COMPLETED",first.get("status"));assertTrue(story(first).contains("与我结盟吗"));
  var kept=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",String.class,sid,a));assertTrue(kept.path("spoken").asBoolean(),"a delivered proposal must not be delivered again after the choice");
  String responseText="我不回答，退开两步。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析")){assertEquals("DECISION_RESPONSE",facts.path("inputPurpose").asText());assertEquals(a,facts.path("decisionContext").path("counterpartId").asText());return response(Map.of("steps",List.of(Map.of("type","ACTION","text",responseText,"localMovement",true))));}
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","你暂未回应，已经退开。"));
   return response(result("你没有答应或拒绝结盟，而是退到石柱后两步。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","石柱后两步")))));
  }).when(model).chatForJson(anyList());
  var next=operations.create(storyRequest("carried-choice-response",responseText));assertEquals("COMPLETED",next.get("status"));assertFalse(story(next).contains("与我结盟吗"));assertEquals("石柱后两步",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
  checkpoints.restore(sid,1);var replay=operations.create(storyRequest("rewound-choice-response",responseText));assertEquals("COMPLETED",replay.get("status"));assertFalse(story(replay).contains("与我结盟吗"));
 }
 @Test void unrelatedNewChapterDoesNotReviveAnOldDecisionOrBindItsCounterpart()throws Exception{
  schedule(a,3600);schedule(b,3600);
  for(boolean latest:List.of(false,true))jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,?,'查看','TALK','NO_OP','narrator：查看',?)",sid,pid,scene,latest?1:0,json.writeValueAsString(latest?Map.of("operationStatus","COMPLETED"):Map.of("storyProgress",Map.of("requiresInput",true,"reason","旧的结盟邀请","counterpartId",a))));
  String text="我查看石柱。";doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析")){var facts=json.readTree(m.get(1).get("content"));assertFalse(facts.has("decisionContext"));assertEquals("ACTION_INPUT",facts.path("inputPurpose").asText());return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));}if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","查看完毕。"));return response(result("你留意石柱。",1,List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(storyRequest("ignore-stale-choice",text)).get("status"));
 }
 @Test void shortDecisionResponseUsesOnlyTheImmediatelyPreviousPublicChoice()throws Exception{
  schedule(a,3600);schedule(b,3600);String prompt="工匠甲向你提出了需要表态的事：与我结盟吗？";
  jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,0,'查看','TALK','NO_OP','narrator：与我结盟吗？',?)",sid,pid,scene,json.writeValueAsString(Map.of("operationStatus","COMPLETED","storyProgress",Map.of("requiresInput",true,"reason",prompt,"decisionType","ALLIANCE","counterpartId",a),"eventTimeline",List.of(Map.of("kind","UTTERANCE","text","工匠甲说：与我结盟吗？")),"PRIVATE_KEY","NPC_PRIVATE_SECRET")));
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析")){var facts=json.readTree(m.get(1).get("content"));assertEquals("DECISION_RESPONSE",facts.path("inputPurpose").asText());assertEquals(a,facts.path("decisionContext").path("counterpartId").asText());assertTrue(facts.path("decisionContext").path("reason").asText().contains("结盟"));assertFalse(m.get(1).get("content").contains("NPC_PRIVATE_SECRET"));return response(Map.of("steps",List.of(Map.of("type","SPEECH","text","我答应你。","targetId",a))));}
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","你已亲自表达了态度。"));throw new AssertionError("A spoken decision response needs no physical result");
  }).when(model).chatForJson(anyList());
  var first=operations.create(storyRequest("short-decision-response","我答应你。"));assertEquals("COMPLETED",first.get("status"));assertTrue(story(first).contains("我答应你"));assertTrue(jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT'",String.class,sid,a).toString().contains("我答应你"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void conversationDirectionAsksTheNpcBeforeSettlingWorldAndSurvivesPlannerInterruption(boolean interrupted)throws Exception{
  acceptedWork(20,List.of());schedule(a,3600);schedule(b,3600);var plans=new AtomicInteger();var authorizations=new AtomicInteger();String text="我原地向工匠甲了解路况，不启程，不交物，也不作承诺。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析")){authorizations.incrementAndGet();assertTrue(json.readTree(m.get(1).get("content")).path("storyMode").asBoolean());return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text,"routineConversation",true,"targetId",a))));}
   if(system.contains("连贯故事节奏规划")){int n=plans.incrementAndGet();if(interrupted && n==1)throw new LLMApiException("HTTP 402",false);return response(n==(interrupted?2:1)?Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text","北边的路况怎样？"):Map.of("kind","DONE","reason","路况已经问明，可以输入下一段行动。"));}
   throw new AssertionError("A conversation direction must not invent the NPC reply in world execution");
  }).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);assertEquals(a,json.readTree(m.get(1).get("content")).path("id").asText());return response(choice("仍在整理，也能回答","北边路滑，靠石柱的那条能走。",player,""));}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("direction-before-world",text));
  if(interrupted){assertEquals("PARTIAL",done.get("status"));assertTrue(json.readTree(payload("direction-before-world")).path("storyConversationPlanning").asBoolean());done=operations.recover(sid,pid,"direction-before-world",0);}
  assertEquals("COMPLETED",done.get("status"));assertEquals(1,authorizations.get());assertTrue(story(done).contains("北边的路况怎样"));assertTrue(story(done).contains("北边路滑"));assertFalse(story(done).contains("原工作实际完成"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));
  verify(model,never()).chatForJson(argThat(messages->messages.get(0).get("content").contains("你结算")));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void naturalStopDoesNotInventFactsOrRequireAReasonField(boolean withReason)throws Exception{
  schedule(a,3600);schedule(b,3600);String text="我查看石柱。",invented="你点头接受了盟约，并把短刀交给对方。";var calls=new AtomicInteger();
  String position=physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position").toString();
  doAnswer(call->{calls.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
   if(system.contains("连贯故事节奏规划"))return response(withReason?Map.of("kind","DONE","reason",invented,"requiresInput",true,"decisionType","ALLIANCE"):Map.of("kind","DONE"));
   return response(result("你看了看石柱表面的纹路。",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));
  }).when(model).chatForJson(anyList());
  String id="grounded-natural-stop-"+withReason;var done=operations.create(storyRequest(id,text));assertEquals("COMPLETED",done.get("status"));
  var progress=json.valueToTree(done).path("ruleSnapshot").path("storyProgress");assertEquals(StoryPacingService.NATURAL_STOP,progress.path("reason").asText());assertFalse(progress.path("requiresInput").asBoolean());assertEquals("",progress.path("decisionType").asText());assertFalse(done.toString().contains(invented));
  assertEquals(position,physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
  int beforeRead=calls.get();var history=transcript.read(sid,pid,null);assertTrue(history.toString().contains(StoryPacingService.NATURAL_STOP));assertFalse(history.toString().contains(invented));assertEquals("COMPLETED",operations.read(sid,pid,id,0L).get("status"));assertEquals(beforeRead,calls.get());verify(model,never()).chatSubForJson(anyList());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void plannerDoesNotAskAnExplicitQuestionAgainWhetherNpcAnswersOrStaysSilent(boolean reply)throws Exception{
  schedule(a,3600);schedule(b,3600);String question="踩实的土路该怎样辨认？";var plans=new AtomicInteger();var replies=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","SPEECH","text",question,"targetId",a))));
   if(system.contains("连贯故事节奏规划")){plans.incrementAndGet();return response(Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text",question));}
   throw new AssertionError("A simple question must not need a world action");
  }).when(model).chatForJson(anyList());
  doAnswer(call->{replies.incrementAndGet();return response(choice("自己的反应",reply?"看地面被脚印压紧的地方。":"",player,""));}).when(model).chatSubForJson(anyList());
  String id="no-explicit-question-replay-"+reply;var done=operations.create(storyRequest(id,question));assertEquals("COMPLETED",done.get("status"));assertEquals(1,replies.get());assertEquals(1,plans.get());
  var saved=json.readTree(payload(id));assertEquals(0,saved.path("storyBeats").asInt());int questions=0;for(var event:saved.path("events"))if("UTTERANCE".equals(event.path("kind").asText()) && player.equals(event.path("actorId").asText())){questions++;assertEquals(a,event.path("targetId").asText());assertEquals(question,event.path("speech").asText());}assertEquals(1,questions);
  assertFalse(json.valueToTree(done).path("ruleSnapshot").path("storyProgress").path("requiresInput").asBoolean());assertEquals(reply,story(done).contains("看地面被脚印压紧的地方"));assertEquals("COMPLETED",operations.read(sid,pid,id,0L).get("status"));assertEquals(1,replies.get());
 }
 void craftingStock()throws Exception{jdbc.update("UPDATE god_profile SET inventory=JSON_ARRAY('布条','小刀') WHERE god_id=?",player);schedule(a,3600);schedule(b,3600);checkpoints.refreshCurrent(sid,pid);}
 Map<String,Object> craftEffect(String cloth,String knife){return Map.of("kind","CRAFT","name","布绷带","materials",List.of(cloth),"tools",List.of(knife),"basis","将布条加工成布绷带");}
 @Test void ordinaryCraftIsPlayableThenGiftableAndRewindRestoresMaterials()throws Exception{
  craftingStock();String text="我用小刀把自己持有的布条加工成布绷带。",gift="我把布绷带送给工匠甲。";var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",facts.path("input").asText()))));
   if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));calls.incrementAndGet();var held=itemsForPlayer();
   if(facts.path("attempt").asText().equals(gift))return response(result("你把布绷带交给工匠甲。",2,List.of(Map.of("kind","TRANSFER","fromId",player,"toId",a,"itemRef",held.get("布绷带"),"mode","GIFT"))));
   assertTrue(system.contains("CRAFT"));assertEquals(held.get("布条"),facts.path("resources").path(player).path("items").findValues("ref").get(0).asText());var r=new LinkedHashMap<>(result("你用小刀将布条加工成布绷带。",4,List.of(craftEffect(held.get("布条"),held.get("小刀")))));r.put("uses",List.of(held.get("布条"),held.get("小刀")));return response(r);
  }).when(model).chatForJson(anyList());
  var first=operations.create(nextInput("craft-default",text));assertEquals("COMPLETED",first.get("status"));assertEquals(List.of("小刀","布绷带"),actors.findById(player).orElseThrow().getInventory());String ref=itemsForPlayer().get("布绷带");assertNotNull(ref);assertTrue(transcript.read(sid,pid,null).toString().contains("加工成布绷带"));assertEquals("COMPLETED",operations.read(sid,pid,"craft-default",0L).get("status"));assertEquals(1,calls.get());
  var delivered=operations.create(nextInput("craft-gift",gift));assertEquals("COMPLETED",delivered.get("status"));assertFalse(actors.findById(player).orElseThrow().getInventory().contains("布绷带"));assertTrue(itemState.owned(sid,actors.findById(a).orElseThrow()).stream().anyMatch(i->i.ref().equals(ref)));
  checkpoints.restore(sid,1);assertEquals(ref,itemsForPlayer().get("布绷带"));assertFalse(actors.findById(a).orElseThrow().getInventory().contains("布绷带"));checkpoints.restore(sid,0);assertEquals(List.of("布条","小刀"),actors.findById(player).orElseThrow().getInventory());assertFalse(itemsForPlayer().containsKey("布绷带"));assertEquals(2,calls.get());
 }
 Map<String,String> itemsForPlayer(){var held=new LinkedHashMap<String,String>();for(var item:itemState.owned(sid,actors.findById(player).orElseThrow()))held.put(item.name(),item.ref());return held;}
 @Test void repeatedCraftingKeepsBothUnitsAndExactGiftAndRewindRemainPlayable()throws Exception{
  craftingStock();jdbc.update("UPDATE god_profile SET inventory=JSON_ARRAY('布条','布条','小刀') WHERE god_id=?",player);
  var initial=itemState.owned(sid,actors.findById(player).orElseThrow());checkpoints.refreshCurrent(sid,pid);String[] giftRef={""};var calls=new AtomicInteger();String text="用小刀将一份布条加工成布绷带。",gift="把第二份布绷带送给工匠甲。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",facts.path("input").asText()))));
   if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));calls.incrementAndGet();
   if(facts.path("attempt").asText().equals(gift))return response(result("你把第二份布绷带交给工匠甲。",2,List.of(Map.of("kind","TRANSFER","fromId",player,"toId",a,"itemRef",giftRef[0],"mode","GIFT"))));
   var held=itemState.owned(sid,actors.findById(player).orElseThrow());String cloth=held.stream().filter(i->"布条".equals(i.name())).findFirst().orElseThrow().ref(),knife=held.stream().filter(i->"小刀".equals(i.name())).findFirst().orElseThrow().ref();
   var result=new LinkedHashMap<>(result("你用小刀将布条加工成布绷带。",4,List.of(craftEffect(cloth,knife))));result.put("uses",List.of(cloth,knife));return response(result);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("repeat-first",text)).get("status"));String firstRef=itemsForPlayer().get("布绷带");
  var secondRequest=nextInput("repeat-second",text);assertEquals("COMPLETED",operations.create(secondRequest).get("status"));
  var outputs=itemState.owned(sid,actors.findById(player).orElseThrow()).stream().filter(i->"布绷带".equals(i.name())).map(ItemStateService.Item::ref).collect(java.util.stream.Collectors.toSet());
  assertEquals(2,outputs.size());assertTrue(outputs.contains(firstRef));giftRef[0]=outputs.stream().filter(r->!firstRef.equals(r)).findFirst().orElseThrow();
  assertEquals("COMPLETED",operations.create(secondRequest).get("status"));assertEquals(2,calls.get());
  assertEquals("COMPLETED",operations.create(nextInput("repeat-gift",gift)).get("status"));assertEquals(firstRef,itemsForPlayer().get("布绷带"));assertEquals(giftRef[0],itemState.owned(sid,actors.findById(a).orElseThrow()).stream().filter(i->"布绷带".equals(i.name())).findFirst().orElseThrow().ref());
  checkpoints.restore(sid,2);assertEquals(outputs,itemState.owned(sid,actors.findById(player).orElseThrow()).stream().filter(i->"布绷带".equals(i.name())).map(ItemStateService.Item::ref).collect(java.util.stream.Collectors.toSet()));
  assertFalse(actors.findById(a).orElseThrow().getInventory().contains("布绷带"));checkpoints.restore(sid,0);assertEquals(initial,itemState.owned(sid,actors.findById(player).orElseThrow()));assertEquals(3,calls.get());
 }
 @Test void gatheringCraftingAndConsumptionRemainPlayableIdempotentAndRewindable()throws Exception{
  schedule(a,3600);schedule(b,3600);jdbc.update("UPDATE game_scene SET scene_summary='树下散落着一根枯枝。工匠甲站在石柱旁。' WHERE scene_id=?",scene);checkpoints.refreshCurrent(sid,pid);var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",facts.path("input").asText()))));
   if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));calls.incrementAndGet();String attempt=facts.path("attempt").asText();
   if(attempt.contains("拾取")){assertEquals(1,facts.path("gatherableResources").size());return response(result("你拾起枯枝，已放入背包。",2,List.of(Map.of("kind","GATHER","sourceRef",facts.path("gatherableResources").get(0).path("ref").asText(),"basis","拾起枯枝"))));}
   assertTrue(facts.path("gatherableResources").isEmpty());String name=attempt.contains("加工")?"枯枝":"木楔",ref=itemsForPlayer().get(name);var r=new LinkedHashMap<>(result(attempt.contains("加工")?"你将枯枝加工成木楔。":"你将木楔用尽。",3,List.of(attempt.contains("加工")?Map.of("kind","CRAFT","name","木楔","materials",List.of(ref),"tools",List.of(),"basis","将枯枝加工成木楔"):Map.of("kind","CONSUME","itemRef",ref,"purpose","USE_UP","basis","将木楔用尽"))));r.put("uses",List.of(ref));return response(r);
  }).when(model).chatForJson(anyList());
  var request=nextInput("resource-gather","拾取地上的枯枝。");var first=operations.create(request);assertEquals("COMPLETED",first.get("status"));String wood=itemsForPlayer().get("枯枝");assertNotNull(wood);assertEquals("COMPLETED",operations.create(request).get("status"));assertEquals(1,calls.get());
  assertEquals("COMPLETED",operations.create(nextInput("resource-craft","把枯枝加工成木楔。" )).get("status"));String wedge=itemsForPlayer().get("木楔");assertNotNull(wedge);
  assertEquals("COMPLETED",operations.create(nextInput("resource-consume","把木楔用尽。" )).get("status"));assertTrue(itemsForPlayer().isEmpty());assertEquals(100,new VitalStateService(jdbc).health(sid,player));
  assertTrue(knownState.current(sid,player,scene).stream().anyMatch(f->wedge.equals(f.get("subject")) && "state".equals(f.get("field")) && "CONSUMED".equals(f.get("value"))));
  assertTrue(knownState.current(sid,a,scene).stream().anyMatch(f->"枯枝".equals(f.get("subject")) && f.get("value").toString().contains("已被拾取")));
  checkpoints.restore(sid,2);assertEquals(wedge,itemsForPlayer().get("木楔"));checkpoints.restore(sid,1);assertEquals(wood,itemsForPlayer().get("枯枝"));checkpoints.restore(sid,0);assertTrue(itemsForPlayer().isEmpty());assertEquals(1,json.valueToTree(effects.facts(sid,scenes.findById(scene).orElseThrow(),player,player)).path("gatherableResources").size());assertEquals(3,calls.get());
 }
 @Test void craftedBandageTreatmentRollsBackWithInvalidTailRecoversOnceAndRewinds()throws Exception{
  craftingStock();jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命','{\"health\":75}','VITAL_STATE','','','',0)",sid,player);checkpoints.refreshCurrent(sid,pid);var treatmentCalls=new AtomicInteger();String text="用小刀将布条加工成布绷带。",treat="用布绷带为自己完成包扎。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",facts.path("input").asText()))));
   if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));var held=itemsForPlayer();var changes=new ArrayList<Object>();String ref;
   if(facts.path("attempt").asText().equals(text)){ref=held.get("布条");changes.add(craftEffect(ref,held.get("小刀")));var r=new LinkedHashMap<>(result("你用小刀将布条加工成布绷带。",4,changes));r.put("uses",List.of(ref,held.get("小刀")));return response(r);}
   assertEquals(10,facts.path("consumptionRules").path("firstAidHealth").asInt());ref=held.get("布绷带");changes.add(Map.of("kind","CONSUME","itemRef",ref,"purpose","FIRST_AID","basis","用布绷带完成包扎"));if(treatmentCalls.getAndIncrement()==0)changes.add(Map.of("kind","UNSUPPORTED_TAIL"));var r=new LinkedHashMap<>(result("你用布绷带完成包扎。",10,changes));r.put("uses",List.of(ref));return response(r);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("aid-craft",text)).get("status"));String bandage=itemsForPlayer().get("布绷带");
  assertEquals("PARTIAL",operations.create(nextInput("aid-use",treat)).get("status"));assertEquals(75,new VitalStateService(jdbc).health(sid,player));assertEquals(bandage,itemsForPlayer().get("布绷带"));
  var done=operations.recover(sid,pid,"aid-use",0);assertEquals("COMPLETED",done.get("status"));assertEquals(85,new VitalStateService(jdbc).health(sid,player));assertFalse(itemsForPlayer().containsKey("布绷带"));
  var runtimeBefore=jdbc.queryForMap("SELECT revision,fence,active_operation FROM game_runtime WHERE session_id=?",sid);
  assertEquals("COMPLETED",operations.recover(sid,pid,"aid-use",0).get("status"));assertEquals("COMPLETED",operations.recoverAsync(sid,pid,"aid-use",0).get("status"));assertEquals(85,new VitalStateService(jdbc).health(sid,player));assertEquals(2,treatmentCalls.get());assertEquals(runtimeBefore,jdbc.queryForMap("SELECT revision,fence,active_operation FROM game_runtime WHERE session_id=?",sid));
  checkpoints.restore(sid,1);assertEquals(75,new VitalStateService(jdbc).health(sid,player));assertEquals(bandage,itemsForPlayer().get("布绷带"));checkpoints.restore(sid,0);assertEquals(List.of("布条","小刀"),actors.findById(player).orElseThrow().getInventory());
 }
 @Test void segmentedFirstAidCountsOnlyVerifiedWorkAndConsumesOnTheFinalSegment()throws Exception{
  schedule(a,3600);schedule(b,3600);jdbc.update("UPDATE god_profile SET inventory=JSON_ARRAY('布绷带') WHERE god_id=?",player);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命','{\"health\":75}','VITAL_STATE','','','',0)",sid,player);checkpoints.refreshCurrent(sid,pid);var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","用布绷带完成包扎。"))));if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE"));if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));var facts=json.readTree(m.get(1).get("content"));int stage=calls.getAndIncrement();assertEquals(stage==0?0:stage==1?8:9,facts.path("completedWorkSeconds").asInt());assertEquals(75,new VitalStateService(jdbc).health(sid,player));String ref=itemsForPlayer().get("布绷带");assertNotNull(ref);var r=new LinkedHashMap<>(result(stage<2?"你继续整理布绷带，包扎尚未完成。":"你用布绷带完成包扎。",stage==0?8:1,stage<2?List.of():List.of(Map.of("kind","CONSUME","itemRef",ref,"purpose","FIRST_AID","basis","用布绷带完成包扎"))));r.put("uses",List.of(ref));if(stage<2){r.put("status","ONGOING");r.put("remainingSeconds",stage==0?2:1);}return response(r);}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("aid-segmented","用布绷带完成包扎。"));assertEquals("COMPLETED",done.get("status"));assertEquals(3,calls.get());assertEquals(10L,done.get("storySeconds"));assertEquals(85,new VitalStateService(jdbc).health(sid,player));assertTrue(itemsForPlayer().isEmpty());
 }
 @Test void failedGatherBatchRestoresSharedSourceAndRecoveryAcquiresOnlyOneItem()throws Exception{
  schedule(a,3600);schedule(b,3600);jdbc.update("UPDATE game_scene SET scene_summary='地上有碎石。' WHERE scene_id=?",scene);checkpoints.refreshCurrent(sid,pid);var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",facts.path("input").asText()))));if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));assertEquals(1,facts.path("gatherableResources").size());var changes=new ArrayList<Object>();changes.add(Map.of("kind","GATHER","sourceRef",facts.path("gatherableResources").get(0).path("ref").asText(),"basis","拾起碎石"));if(calls.getAndIncrement()==0)changes.add(Map.of("kind","UNSUPPORTED_TAIL"));return response(result("你拾起碎石，已放入背包。",2,changes));}).when(model).chatForJson(anyList());
  var partial=operations.create(nextInput("gather-rollback","拾起地上的碎石。"));assertEquals("PARTIAL",partial.get("status"));assertTrue(itemsForPlayer().isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='RESOURCE_TAKEN'",Integer.class,sid));assertFalse(story(partial).contains("已放入背包"));
  assertEquals("COMPLETED",operations.recover(sid,pid,"gather-rollback",0).get("status"));assertEquals(List.of("碎石"),actors.findById(player).orElseThrow().getInventory());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='RESOURCE_TAKEN'",Integer.class,sid));assertEquals(2,calls.get());
 }
 @Test void failedCraftBatchRollsBackMaterialAndRecoveryCreatesOnlyOneOutput()throws Exception{
  craftingStock();String text="我用小刀将布条加工成布绷带。";var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));if(!system.contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));var held=itemsForPlayer();var changes=new ArrayList<Object>();changes.add(craftEffect(held.get("布条"),held.get("小刀")));if(worlds.getAndIncrement()==0)changes.add(Map.of("kind","UNSUPPORTED_TAIL"));var r=new LinkedHashMap<>(result("你用小刀将布条加工成布绷带。",4,changes));r.put("uses",List.of(held.get("布条"),held.get("小刀")));return response(r);
  }).when(model).chatForJson(anyList());
  var partial=operations.create(nextInput("craft-rollback",text));assertEquals("PARTIAL",partial.get("status"));assertEquals(List.of("布条","小刀"),actors.findById(player).orElseThrow().getInventory());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.name'))='布绷带'",Integer.class,sid));assertFalse(story(partial).contains("加工成布绷带"));
  var done=operations.recover(sid,pid,"craft-rollback",0);assertEquals("COMPLETED",done.get("status"));assertEquals(2,worlds.get());assertEquals(List.of("小刀","布绷带"),actors.findById(player).orElseThrow().getInventory());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.name'))='布绷带'",Integer.class,sid));
 }
 @Test void aLongCraftConsumesMaterialsOnlyWhenTheFinalSegmentCompletes()throws Exception{
  craftingStock();String text="我用小刀将布条加工成布绷带。";var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE"));var held=itemsForPlayer();assertTrue(held.containsKey("布条"));var r=new LinkedHashMap<String,Object>();
   if(worlds.getAndIncrement()==0){r.putAll(result("你开始整理布条，尚未完成。",3,List.of()));r.put("status","ONGOING");r.put("remainingSeconds",2);}
   else r.putAll(result("你用小刀将布条加工成布绷带。",2,List.of(craftEffect(held.get("布条"),held.get("小刀")))));
   r.put("uses",List.of(held.get("布条"),held.get("小刀")));return response(r);
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("craft-progress",text));assertEquals("COMPLETED",done.get("status"));assertEquals(2,worlds.get());assertEquals(5L,done.get("storySeconds"));assertEquals(List.of("小刀","布绷带"),actors.findById(player).orElseThrow().getInventory());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_TASK'",Integer.class,sid,player));
 }
 @Test void storyModeAsksNaturalInjuryQuestionsAndContinuesTheReplyWithoutAnotherHumanInput()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','身体生命','{\"health\":75}','VITAL_STATE','','','',0)",sid,a);
  jdbc.update("UPDATE game_scene SET scene_summary='工匠甲胳膊擦伤，仍然清醒；守望者乙站在石柱旁。' WHERE scene_id=?",scene);
  schedule(a,3600);schedule(b,3600);var plans=new AtomicInteger();String text="我了解朋友的伤情。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("连贯故事节奏规划")){assertFalse(m.get(1).get("content").contains("NPC_PRIVATE_SECRET"));return response(plans.getAndIncrement()==0?Map.of("kind","SPEECH","routineKind","CARE","targetId",a,"text","你为何受伤，是谁伤了你？"):Map.of("kind","DONE","reason","伤因已经问明，可以决定接下来去哪里。"));}
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
   assertTrue(m.get(1).get("content").contains("narrativePacing"));return response(result("你在朋友身旁停下，留意他胳膊上的擦伤和神情，先确认他仍然清醒。",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var decision=a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("NPC_PRIVATE_SECRET","山路太滑，我自己摔的，没有人害我。",player,""):choice("","","","");decision.put("player_decision",null);return response(decision);}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("routine-injury",text));assertEquals("COMPLETED",done.get("status"));assertEquals(2,plans.get());assertTrue(story(done).contains("你为何受伤，是谁伤了你"));assertTrue(story(done).contains("我自己摔的"));assertFalse(story(done).contains("NPC_PRIVATE_SECRET"));var progress=(Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress");assertEquals(false,progress.get("requiresInput"));assertEquals(75,new VitalStateService(jdbc).health(sid,a));
  var history=(List<Map<String,Object>>)transcript.read(sid,pid,null).get("turns");assertTrue(history.get(0).get("storyProgress").toString().contains(StoryPacingService.NATURAL_STOP));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ALLIANCE","HOSTILITY","ROMANCE"})
 void majorInvitationsStopBeforeThePlayerAutomaticallyResponds(String type)throws Exception{
  schedule(b,3600);String text="我查看眼前情况。";var plans=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("连贯故事节奏规划")){plans.incrementAndGet();throw new AssertionError("Delivered major choice must stop before routine planning");}if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));return response(result("你查看石柱边的情况。",1,List.of()));}).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("","","",""));var d=choice("自己的立场","我有一件需要你亲自表态的事。",player,"");d.put("player_decision",Map.of("type",type,"question","PRIVATE_PROPOSAL_DETAIL：你准备如何回应？"));return response(d);}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("major-"+type,text));assertEquals("COMPLETED",done.get("status"));assertEquals(0,plans.get());assertFalse(story(done).contains("你说"));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());var progress=(Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress");assertEquals(true,progress.get("requiresInput"));assertEquals(type,progress.get("decisionType"));assertFalse(progress.toString().contains("PRIVATE_PROPOSAL_DETAIL"));assertTrue(progress.toString().contains("我有一件需要你亲自表态的事"));
 }
 @Test void unfinishedAuthorizedWorkContinuesInTheSameChapterButStopsAfterItsRealCompletion()throws Exception{
  schedule(a,3600);schedule(b,3600);String text="我仔细检查石柱，直到看清纹路。";var actions=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","已经看清纹路，可以输入接下来的大致行动。"));if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));int index=actions.getAndIncrement();var r=new LinkedHashMap<String,Object>(result("你继续查看第"+(index+1)+"处纹路。",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));if(index<2){r.put("status","ONGOING");r.put("remainingSeconds",2-index);}return response(r);}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("long-authorized-work",text));assertEquals("COMPLETED",done.get("status"));assertEquals(3,actions.get());assertEquals(3L,done.get("storySeconds"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_TASK'",Integer.class,sid,player));
 }
 @Test void previousPlayerWorkCannotResumeAgainstANewUnrelatedAction()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'v2:work','旧动作',?,'ROLE_TASK','',?,'',0)",sid,player,json.writeValueAsString(Map.of("attempt","旧的擦拭工作","actual",Map.of("status","ONGOING","remainingSeconds",20))),scene);schedule(a,3600);schedule(b,3600);var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析")){assertFalse(json.readTree(m.get(1).get("content")).path("pendingPlayerWork").path("ref").asText().isBlank());return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我观察石柱。"))));}assertFalse(m.get(1).get("content").contains("旧的擦拭工作"));calls.incrementAndGet();return response(result("你观察石柱。",1,List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("new-not-old","我观察石柱。")).get("status"));assertEquals(1,calls.get());
 }
 @Test void routineDialogueCannotRunForeverEvenWhenTheMainModelKeepsProposingQuestions()throws Exception{
  schedule(a,3600);schedule(b,3600);var plans=new AtomicInteger();String text="我与朋友了解情况。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("连贯故事节奏规划")){plans.incrementAndGet();return response(Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text","还有什么需要我知道的吗？"));}if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));return response(result("你留意朋友的近况。",1,List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("bounded-story",text));assertEquals("COMPLETED",done.get("status"));assertEquals(2,plans.get());assertEquals(1,json.readTree(payload("bounded-story")).path("storyBeats").asInt());
  assertEquals(1,((List<?>)done.get("lines")).stream().filter(line->line.toString().contains("还有什么需要我知道的吗")).count());
 }
 @Test void unconsciousPlayerStopsWithoutPayingForRoutinePlanningOrSpeech()throws Exception{
  schedule(a,3600);schedule(b,3600);String text="我原地躺下睡觉。";var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");calls.incrementAndGet();
   if(system.contains("连贯故事节奏规划"))throw new AssertionError("An unconscious player must not enter the speech planner");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
   return response(result("你在原地躺下，闭眼睡去。",1,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("awake",true),"after",Map.of("awake",false)))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("sleep-story",text));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get());assertEquals(0,json.readTree(payload("sleep-story")).path("storyBeats").asInt());
  var progress=(Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress");assertEquals(false,progress.get("requiresInput"));assertTrue(progress.get("reason").toString().contains("无法继续交流"));
 }
 @Test void distinctRoutineQuestionsStillStopAtTheThreeBeatBoundary()throws Exception{
  schedule(a,3600);schedule(b,3600);var plans=new AtomicInteger();String text="我与朋友了解情况。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text","关于第"+plans.incrementAndGet()+"处路口，你还知道什么？"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
   return response(result("你留意朋友的近况。",1,List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("distinct-bounded-story",text));assertEquals("COMPLETED",done.get("status"));assertEquals(3,plans.get());assertEquals(3,json.readTree(payload("distinct-bounded-story")).path("storyBeats").asInt());
 }
 @Test void aVisibleHostileAttemptCreatesAHumanDecisionBeforeAutomaticCombatSettlement()throws Exception{
  schedule(b,3600);String text="我查看眼前情况。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));return response(result("你查看石柱边的情况。",1,List.of()));}).when(model).chatForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("","","",""));var d=choice("决定敌对","",player,"挥拳攻向玩家。PRIVATE_ATTACK_PLAN：准备随后偷偷翻走物品");d.put("contest",Map.of("kind","ATTACK","targetId",player,"statKey","DOU"));return response(d);}).when(model).chatSubForJson(anyList());
  var done=operations.create(storyRequest("hostile-boundary",text));assertEquals("COMPLETED",done.get("status"));assertEquals(100,((Map<?,?>)done.get("scene")).get("playerHealth"));assertTrue(story(done).contains("开始尝试"));assertFalse(story(done).contains("PRIVATE_ATTACK_PLAN"));assertEquals("HOSTILITY",((Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress")).get("decisionType"));
 }
 @Test @org.springframework.transaction.annotation.Transactional void largeRoleHistorySortsKeysInsteadOfJsonBodies()throws Exception{
  long original=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);try{jdbc.execute("SET SESSION sort_buffer_size=32768");for(int i=0;i<30;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'长历史',?,'ROLE_PROFILE','',?,'',?)",sid,a,"large-history-"+i,json.writeValueAsString(Map.of("index",i,"text","x".repeat(50000))),scene,i);
   var method=ConversationOperationService.class.getDeclaredMethod("recentRoleRecords",String.class,String.class);method.setAccessible(true);var rows=(List<Map<String,Object>>)method.invoke(operations,sid,a);assertEquals(24,rows.size());assertEquals(29,json.readTree(rows.get(0).get("content").toString()).path("index").asInt());assertEquals(6,json.readTree(rows.get(23).get("content").toString()).path("index").asInt());
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+original);}
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={2,4})
 void completedStoryOpeningStopsBeforeOptionalQuestionsExhaustTheBudget(int limit)throws Exception{
  schedule(a,3600);schedule(b,3600);var paid=new AtomicInteger();var actions=new AtomicInteger();String text="我查看石柱。";org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",limit);
  doAnswer(call->{try(var billed=LLMUsage.startCall("story-budget")){paid.incrementAndGet();billed.response(new LLMResponse("",null,10,2,12,0.001),true);List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","DONE","reason","已经查看完毕，可以输入下一段方向。"));if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));actions.incrementAndGet();return response(result("你查看了石柱上的旧刻痕。",1,List.of()));}}).when(model).chatForJson(anyList());
  try{var partial=operations.create(storyRequest("story-budget",text));assertEquals("COMPLETED",partial.get("status"));assertEquals(2,paid.get());assertEquals(1,actions.get());assertTrue(story(partial).contains("旧刻痕"));assertTrue(json.readTree(payload("story-budget")).has("storyBudgetBoundary"));
   var done=operations.recover(sid,pid,"story-budget",0);assertEquals("COMPLETED",done.get("status"));assertEquals(2,paid.get());assertEquals(1,actions.get());assertEquals(1L,done.get("storySeconds"));
  }finally{org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",24);}
 }
 @Test void unexpectedBudgetExhaustionInOptionalDialogueParksTheAcceptedNpcChoice()throws Exception{
  schedule(a,3600);schedule(b,3600);var paid=new AtomicInteger();String text="我查看石柱。";
  doAnswer(call->{for(int i=0;i<21;i++)try(var bill=LLMUsage.startCall("optional-role")){paid.incrementAndGet();bill.response(new LLMResponse("",null,1,1,2,0.001),true);}return response(choice("已选的独立反应","",player,"整理衣物"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{try(var bill=LLMUsage.startCall("optional-main")){paid.incrementAndGet();bill.response(new LLMResponse("",null,1,1,2,0.001),true);List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));if(system.contains("连贯故事节奏规划"))return response(Map.of("kind","SPEECH","routineKind","INFORMATION","targetId",a,"text","你接下来做什么？"));return response(result("你实际查看了石柱。",1,List.of()));}}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("optional-cap",text));assertEquals("COMPLETED",done.get("status"));assertEquals(24,paid.get());assertTrue(json.readTree(payload("optional-cap")).path("storyBudgetBoundary").path("exhaustedDuringOptionalContinuation").asBoolean());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PENDING' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.decisions.action_text'))='整理衣物'",Integer.class,sid,a));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"second-action","wait-then-action"})
 void storyBudgetCannotPretendUnfinishedExplicitInputWasCompleted(String mode)throws Exception{
  schedule(a,3600);schedule(b,3600);var paid=new AtomicInteger();String first=mode.equals("second-action")?"查看石柱。":"等待3秒。",second="检查绳结。",text=first+second;int allowance=mode.equals("second-action")?2:1;org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",allowance);
  doAnswer(call->{try(var bill=LLMUsage.startCall("explicit-budget")){paid.incrementAndGet();bill.response(new LLMResponse("",null,1,1,2,0.001),true);List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(mode.equals("second-action")?Map.of("type","ACTION","text",first):Map.of("type","WAIT","text",first,"seconds",3),Map.of("type","ACTION","text",second))));assertFalse(m.get(0).get("content").contains("连贯故事节奏规划"));return response(result("实际完成本次查看。",1,List.of()));}}).when(model).chatForJson(anyList());
  try{var partial=operations.create(storyRequest("explicit-budget",text));assertEquals("PARTIAL",partial.get("status"));assertEquals("MODEL_CALL_LIMIT",partial.get("pendingKind"));assertFalse(json.readTree(payload("explicit-budget")).has("storyStop"));var done=operations.recover(sid,pid,"explicit-budget",0);assertEquals("COMPLETED",done.get("status"));assertEquals(allowance+1,paid.get());}finally{org.springframework.test.util.ReflectionTestUtils.setField(usage,"maxModelCalls",24);}
 }
 @Test void privateThoughtsAreNotReinterpretedAsRoutineConversationAuthority()throws Exception{
  schedule(a,3600);schedule(b,3600);String privatePart="我只在心里想秘密暗号，不说出口。",action="然后查看朋友。";var plans=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("连贯故事节奏规划")){plans.incrementAndGet();assertFalse(m.get(1).get("content").contains("秘密暗号"));assertTrue(m.get(1).get("content").contains(action));return response(Map.of("kind","DONE","reason","查看之后可以决定下一段行动。"));}if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","PRIVATE_INTENT","text",privatePart),Map.of("type","ACTION","text",action))));return response(result("你留意朋友的情况。",1,List.of()));}).when(model).chatForJson(anyList());
  var review=operations.create(storyRequest("private-story",privatePart+action));assertEquals("INPUT_REVIEW_REQUIRED",review.get("pendingKind"));var done=operations.reviewInput(sid,pid,"private-story",0,inputReview(review).get("reviewId").toString(),"KEEP_PRIVATE",false);assertEquals("COMPLETED",done.get("status"));assertEquals(1,plans.get());assertFalse(story(done).contains("秘密暗号"));
 }
 @Test void olderChapterKeepsItsStoredCostsWithoutInventingCallCounts()throws Exception{
  var done=operations.create(talk("legacy-fees"));long log=((Number)done.get("logId")).longValue();jdbc.update("UPDATE interaction_log SET token_input=100,token_output=25,token_total=125,cost_cny=0.015 WHERE log_id=?",log);
  var saved=usage.forLog(sid,log);assertEquals(125,((Number)saved.get("total")).intValue());assertEquals(0,((Number)saved.get("calls")).intValue());assertEquals(true,saved.get("legacy"));
 }

 JsonNode realRegressionFixture()throws Exception{return json.readTree(getClass().getResourceAsStream("/real-regressions-20261015.json"));}
 Map<?,?> inputReview(Map<String,Object> result){return (Map<?,?>)result.get("inputReview");}
 void privateInterpretation(String text)throws Exception{
  var packet=Map.of("steps",List.of(Map.of("type","PRIVATE_INTENT","text",text)));
  doReturn(response(packet)).when(model).chatForJson(anyList());
 }
 @Test void realRoundSevenIsReviewableRatherThanSilentlyCompleted()throws Exception{
  var fixture=realRegressionFixture();String text=fixture.path("thoughtAuthorization").path("rawText").asText();doReturn(response(fixture.path("thoughtAuthorization"))).when(model).chatForJson(anyList());
  var review=operations.create(nextInput("input-real-seven",text));assertEquals("PARTIAL",review.get("status"));assertEquals("INPUT_REVIEW_REQUIRED",review.get("pendingKind"));assertEquals(0L,review.get("storySeconds"));assertTrue(inputReview(review).get("privateSegments").toString().contains(text));
  record("offline-original-round-seven-review",review);
  assertTrue(((List<?>)((Map<?,?>)review.get("ruleSnapshot")).get("technicalIssues")).isEmpty());verify(model,times(1)).chatForJson(anyList());verify(model,never()).chatSubForJson(anyList());
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->operations.recover(sid,pid,"input-real-seven",0));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void confirmedPrivateInputIsPrivateIdempotentAndDoesNotWakeWork()throws Exception{
  acceptedWork(20,List.of());var previousChoice=choice("此前已接受的想法","此前已选的答复",player,"");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'pending:previous','工作',?,'ROLE_PENDING','',?,'',0)",sid,b,json.writeValueAsString(Map.of("schemaVersion",2,"originDecisionId","previous:choice","decisions",previousChoice,"jobTimes",Map.of("start",0,"sceneId",scene),"spokenJobs",false,"executedJobs",false)),scene);
  String text="我先在心里想想要不要借刀。";privateInterpretation(text);var review=operations.create(nextInput("input-private",text));String reviewId=inputReview(review).get("reviewId").toString();
  var done=operations.reviewInput(sid,pid,"input-private",0,reviewId,"KEEP_PRIVATE",false);assertEquals("COMPLETED",done.get("status"));assertEquals(0L,done.get("storySeconds"));assertEquals("",story(done));assertTrue(((Map<?,?>)done.get("ruleSnapshot")).get("inputNotice").toString().contains("尚未执行"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD'",Integer.class,sid));
  var pending=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PENDING'",String.class,sid,b));assertEquals(json.valueToTree(previousChoice),pending.path("decisions"));assertFalse(pending.path("spokenJobs").asBoolean());
  assertEquals("COMPLETED",operations.reviewInput(sid,pid,"input-private",0,reviewId,"KEEP_PRIVATE",false).get("status"));verify(model,times(1)).chatForJson(anyList());verify(model,never()).chatSubForJson(anyList());
  var history=(List<Map<String,Object>>)transcript.read(sid,pid,null).get("turns");assertTrue(history.get(0).get("inputNotice").toString().contains("未告知"));
 }
 @Test void reinterpretingTheRealInputRetainsAllOriginalConstraintsAndExecutesOnce()throws Exception{
  String text="我想用布条擦自己的手，不接触别人的物品。";var calls=new AtomicInteger();var resultCalls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String system=messages.get(0).get("content");
   if(system.contains("玩家输入授权解析")){int n=calls.incrementAndGet();assertTrue(messages.get(1).get("content").contains(text));if(n==1)return response(Map.of("steps",List.of(Map.of("type","PRIVATE_INTENT","text",text))));assertTrue(messages.get(1).get("content").contains("ACTION_REQUEST"));return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));}
   resultCalls.incrementAndGet();assertTrue(messages.get(1).get("content").contains(text));return response(result("旅人用自己的布条擦手，没有触碰别人的物品。",2,List.of()));
  }).when(model).chatForJson(anyList());
  var review=operations.create(nextInput("input-action",text));String reviewId=inputReview(review).get("reviewId").toString();var done=operations.reviewInput(sid,pid,"input-action",0,reviewId,"REINTERPRET_ACTION",false);
  assertEquals("COMPLETED",done.get("status"));assertEquals(2L,done.get("storySeconds"));assertEquals(2,calls.get());assertEquals(1,resultCalls.get());assertTrue(story(done).contains("没有触碰别人的物品"));
  operations.reviewInput(sid,pid,"input-action",0,reviewId,"REINTERPRET_ACTION",false);assertEquals(2,calls.get());assertEquals(1,resultCalls.get());
 }
 @Test void oldInputConfirmationCannotConfirmANewerInterpretationOrCancelledOperation()throws Exception{
  String text="我想看看绳结。";privateInterpretation(text);var first=operations.create(nextInput("review-version",text));String old=inputReview(first).get("reviewId").toString();
  var second=operations.reviewInput(sid,pid,"review-version",0,old,"REINTERPRET_ACTION",false);String fresh=inputReview(second).get("reviewId").toString();assertNotEquals(old,fresh);
  assertEquals(fresh,inputReview(operations.reviewInput(sid,pid,"review-version",0,old,"REINTERPRET_ACTION",false)).get("reviewId"));assertThrows(com.bs.narrative.exception.BusinessException.class,()->operations.reviewInput(sid,pid,"review-version",0,old,"KEEP_PRIVATE",false));
  operations.cancel(sid,pid,"review-version",0);assertThrows(com.bs.narrative.exception.BusinessException.class,()->operations.reviewInput(sid,pid,"review-version",0,fresh,"KEEP_PRIVATE",false));verify(model,times(2)).chatForJson(anyList());
 }
 @Test void mixedPrivateAndConditionalInputWaitsForConfirmationBeforeAnySpeechOrTime()throws Exception{
  String input="我先想想，等他答应，再观察";var packet=Map.of("steps",List.of(Map.of("type","PRIVATE_INTENT","text","我先想想"),Map.of("type","WAIT","text","等他答应","condition",Map.of("type","SEMANTIC_EVENT","text","他答应")),Map.of("type","ACTION","text","再观察","whenWaitSatisfied",true,"conditionText","他答应")));
  doReturn(response(packet)).when(model).chatForJson(anyList());var review=operations.create(nextInput("mixed-review",input));assertEquals("INPUT_REVIEW_REQUIRED",review.get("pendingKind"));assertEquals(0L,review.get("storySeconds"));assertEquals("",story(review));assertFalse(json.readTree(payload("mixed-review")).has("authorization"));
  var preserved=json.readTree(payload("mixed-review")).path("authorizationReview");assertTrue(preserved.path("steps").get(2).path("whenWaitSatisfied").asBoolean());assertEquals("他答应",preserved.path("steps").get(2).path("conditionText").asText());
  var done=operations.reviewInput(sid,pid,"mixed-review",0,inputReview(review).get("reviewId").toString(),"KEEP_PRIVATE",false);assertEquals("COMPLETED",done.get("status"));assertEquals(30L,done.get("storySeconds"));assertFalse(story(done).contains("检查了绳结"));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void foreignAccountAndRewoundBranchCannotConfirmInput()throws Exception{
  privateInterpretation("我想想。");var first=operations.create(nextInput("review-owner","我想想。"));String reviewId=inputReview(first).get("reviewId").toString();
  assertThrows(RuntimeException.class,()->operations.reviewInput(sid,"someone-else","review-owner",0,reviewId,"KEEP_PRIVATE",false));checkpoints.restore(sid,0);assertThrows(RuntimeException.class,()->operations.reviewInput(sid,pid,"review-owner",0,reviewId,"KEEP_PRIVATE",false));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void realMistypedObservationIdIsBoundToTheActualLocalScene()throws Exception{
  var candidate=realRegressionFixture().path("observation").deepCopy();var observed=(com.fasterxml.jackson.databind.node.ObjectNode)candidate.path("effects").get(0);String bad=observed.path("sceneId").asText();observed.put("actorId",player);
  effects.apply(sid,scenes.findById(scene).orElseThrow(),player,player,candidate,new HashSet<>());assertNotEquals(scene,bad);assertEquals(scene,observed.path("sceneId").asText());assertEquals(player,observed.path("actorId").asText());
 }
 @Test void localObservationNeverSelectsAnotherSceneAndRejectsAnotherActor()throws Exception{
  String remote="remote_"+sid;jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处','[]','不能隔空观察的地方',1,0)",remote,sid);
  var location=scenes.findById(scene).orElseThrow();var packet=json.valueToTree(result("观察眼前",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE","sceneId",remote))));effects.apply(sid,location,player,player,packet,new HashSet<>());assertEquals(scene,packet.path("effects").get(0).path("sceneId").asText());assertEquals(player,packet.path("effects").get(0).path("actorId").asText());
  ((com.fasterxml.jackson.databind.node.ObjectNode)packet.path("effects").get(0)).put("actorId",b);assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,player,player,packet,new HashSet<>()));
  var missing=json.valueToTree(result("观察眼前",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));effects.apply(sid,location,player,player,missing,new HashSet<>());assertEquals(scene,missing.path("effects").get(0).path("sceneId").asText());
  ((com.fasterxml.jackson.databind.node.ObjectNode)missing.path("effects").get(0)).put("scope","REMOTE_SCENE");assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,player,player,missing,new HashSet<>()));
 }
 @Test void localObservationCannotUseAStalePlayerSceneOrAnUnconsciousBody()throws Exception{
  String remote="away_"+sid; jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'远处','[]','远处')",remote,sid); var packet=json.valueToTree(result("观察眼前",1,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));var location=scenes.findById(scene).orElseThrow();jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE player_id=?",remote,pid);assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,player,player,packet,new HashSet<>()));
  jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE player_id=?",scene,pid);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'body:v2','body','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,player,scene);assertThrows(IllegalArgumentException.class,()->effects.apply(sid,location,player,player,packet,new HashSet<>()));
 }
 @Test void movingThenObservingUsesTheActuallyReachedScene()throws Exception{
  String remote="move_"+sid;jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外',1,0)",remote,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,scene,remote);
  var packet=json.valueToTree(result("前往门外并观察眼前",2,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",remote),Map.of("kind","OBSERVE","scope","VISIBLE_SCENE","sceneId","model-copy-error"))));effects.apply(sid,scenes.findById(scene).orElseThrow(),player,player,packet,new HashSet<>());
  assertEquals(remote,packet.path("effects").get(1).path("sceneId").asText());assertEquals(remote,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void crossingASceneBoundaryDoesNotCarryThePreviousLocalActivityOrView(boolean playerMoves)throws Exception{
  String mover=playerMoves?player:a,remote="move_"+sid;
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外',1,0)",remote,sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,scene,remote);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'WEST','返回')",sid,remote,scene);
  var body=Map.of("awake",true,"bound",false,"position","瀑边碎石间","activity","立于瀑边碎石间，停步");
  var view=new LinkedHashMap<String,Object>(body);view.put("percepts",List.of("瀑边的旧见闻"));view.put("execution",Map.of("task","查看瀑边石标","status","BLOCKED"));
  for(var record:Map.of("body:v2",body,"view:"+scene,view).entrySet())jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧地点状态',?,?, '',?,'旧地点',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,mover,record.getKey(),json.writeValueAsString(record.getValue()),record.getKey().startsWith("body")?"BODY_STATE":"SCENE_VIEW",scene);
  for(var route:List.of(List.of(scene,remote),List.of(remote,scene))){
   var from=scenes.findById(route.get(0)).orElseThrow();var to=scenes.findById(route.get(1)).orElseThrow();
   var packet=json.valueToTree(result("沿路抵达。",2,List.of(Map.of("kind","MOVE","actorId",mover,"fromSceneId",from.getSceneId(),"toSceneId",to.getSceneId()))));
   effects.apply(sid,from,mover,player,packet,new HashSet<>());
   var arrived=physical.view(sid,actors.findById(mover).orElseThrow(),to);
   assertEquals("抵达"+to.getLocationName(),arrived.get("position"));assertEquals(body.get("activity"),arrived.get("activity"));assertEquals(true,arrived.get("activityHistorical"));
   assertEquals(List.of(),arrived.get("percepts"));assertEquals(Map.of(),arrived.get("execution"));assertEquals(true,arrived.get("awake"));assertEquals(false,arrived.get("bound"));
   assertEquals(to.getSceneId(),jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='body:v2'",String.class,sid,mover));
   assertEquals(to.getLocationName(),jdbc.queryForObject("SELECT location_name FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='body:v2'",String.class,sid,mover));
  }
  var here=scenes.findById(scene).orElseThrow();
  effects.apply(sid,here,mover,player,json.valueToTree(result("在当前地点检查自己的身体。",1,List.of(Map.of("kind","BODY","actorId",mover,"before",Map.of("activity",body.get("activity")),"after",Map.of("activity","检查自己的脚踝"))))),new HashSet<>());
  assertEquals(false,physical.view(sid,actors.findById(mover).orElseThrow(),here).get("activityHistorical"));verifyNoInteractions(model);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PUBLIC","PRIVATE"})
 void sameInstantObservationIncludesTheEntireCommittedGroupEvenWithoutAPublicActionNotice(String visibility)throws Exception{
  acceptedWork(2,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE")));schedule(a,3600);schedule(b,3600);
  String old=physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position").toString();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我退到石柱外沿。"))));
   var packet=new LinkedHashMap<>(result("你退到石柱外沿。",2,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",old),"after",Map.of("position","石柱外沿")))));packet.put("visibility",visibility);return response(packet);
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("same-instant-scan-"+visibility,"我退到石柱外沿。"));assertEquals("COMPLETED",done.get("status"));assertEquals(2L,done.get("storySeconds"));
  var observed=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='OBSERVED_STATE'",String.class,sid,a,"observed:"+scene+":"+player));
  assertEquals("石柱外沿",observed.path("position").asText());assertEquals(2,observed.path("observedAtSeconds").asLong());
 }
 @Test void futureMalformedBodyCacheDoesNotSpendARepairBeforeItsAuthorizedBoundary()throws Exception{
  String old=physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position").toString();acceptedWork(20,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("position",old),"after",Map.of("position",old,"activity","拢好衣物"))));schedule(a,3600);schedule(b,3600);var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我看看自己的衣领。"))));assertEquals(player,json.readTree(m.get(1).get("content")).path("actorId").asText());worlds.incrementAndGet();return response(result("你查看了衣领。",1,List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("future-body-cache","我看看自己的衣领。"));assertEquals("COMPLETED",done.get("status"));assertEquals(1L,done.get("storySeconds"));assertEquals(1,worlds.get());assertEquals(0,json.readTree(payload("future-body-cache")).path("worldRevalidationAttempts").asInt());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void malformedCachedBodyPacketsAreRevalidatedTogetherWithOneRepairPerJob(boolean remainsInvalid)throws Exception{
  var sceneEntity=scenes.findById(scene).orElseThrow();String old=physical.view(sid,actors.findById(a).orElseThrow(),sceneEntity).get("position").toString();
  var bad=Map.of("kind","BODY","actorId",a,"before",Map.of("position",old),"after",Map.of("position",old,"activity","拢好衣物"));acceptedWork(2,List.of(bad));
  var first=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",String.class,sid,a));var other=(com.fasterxml.jackson.databind.node.ObjectNode)first.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)other.path("execution").path("effects").get(0)).put("actorId",b);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'other-body-cache','旧工作',?,'ROLE_PROCESS','',?,'',0)",sid,b,other.toString(),scene);schedule(a,3600);schedule(b,3600);
  var calls=new ConcurrentHashMap<String,AtomicInteger>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待5秒","seconds",5))));var f=json.readTree(m.get(1).get("content"));String role=f.path("actorId").asText();calls.computeIfAbsent(role,k->new AtomicInteger()).incrementAndGet();assertEquals("body effect needs matching before field",f.path("rejectedResultReason").asText());
   var after=Map.of("activity","拢好衣物");var before=remainsInvalid?Map.of():Map.of("activity",f.path("resources").path(role).path("body").path("activity").asText());return response(result("各自拢好衣物。",2,List.of(Map.of("kind","BODY","actorId",role,"before",before,"after",after))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("cached-body-repair-"+remainsInvalid,"等待5秒"));assertEquals(remainsInvalid?"PARTIAL":"COMPLETED",done.get("status"));assertEquals(1,calls.get(a).get());assertEquals(1,calls.get(b).get());verify(model,never()).chatSubForJson(anyList());
  var saved=json.readTree(payload("cached-body-repair-"+remainsInvalid));assertEquals(2,saved.path("worldRevalidationAttempts").asInt());assertEquals(remainsInvalid?0:5,saved.path("endSeconds").asLong());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void actualObservationMustUpdateSightAndCannotBeReplacedByActivityProse(boolean legacy)throws Exception{
  schedule(a,3600);schedule(b,3600);String text=legacy?"我留在原地，仔细观察眼前人物；只看不动。":"我留意石柱附近的可见动静。";var worlds=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析")){var step=new LinkedHashMap<String,Object>(Map.of("type","ACTION","text",text));if(!legacy)step.put("localObservation",true);return response(Map.of("steps",List.of(step)));}
   worlds.incrementAndGet();var facts=json.readTree(m.get(1).get("content"));assertTrue(facts.path("selectedLocalObservation").asText().contains("OBSERVE"));return response(result("你留意现场。",2,List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("observation-needs-state-"+legacy,text));assertEquals("COMPLETED",done.get("status"));assertEquals(1,worlds.get());assertEquals(2L,done.get("storySeconds"));var saved=json.readTree(payload("observation-needs-state-"+legacy));assertEquals(1,saved.path("events").findValuesAsText("kind").stream().filter("ACTION_RESULT"::equals).count());assertTrue(story(done).contains("你留意现场。"));assertEquals(1,saved.path("serverObservationBindings").size());assertEquals(0,saved.path("worldRevalidationAttempts").asInt());assertEquals(1,saved.path("events").findValuesAsText("kind").stream().filter("OBSERVE"::equals).count());
 }
 @Test void acceptedCachedObservationRefreshesSightWithoutAnotherModelCall()throws Exception{
  acceptedWork(2,List.of());var record=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",String.class,sid,a));((com.fasterxml.jackson.databind.node.ObjectNode)record.path("decision")).put("action_text","查看当前可见现场").put("localObservation",true);jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",record.toString(),sid,a);schedule(a,3600);schedule(b,3600);waitFor(3);
  var done=operations.create(nextInput("cached-observation-binding","等待3秒"));assertEquals("COMPLETED",done.get("status"));var saved=json.readTree(payload("cached-observation-binding"));assertEquals(1,saved.path("serverObservationBindings").size());assertEquals(0,saved.path("worldRevalidationAttempts").asInt());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='OBSERVED_STATE'",Integer.class,sid,a,"observed:"+scene+":"+player));verify(model,times(1)).chatForJson(anyList());verify(model,never()).chatSubForJson(anyList());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void observationBindingCannotTurnABlockedOrUnselectedActionIntoSight(boolean blocked)throws Exception{
  schedule(a,3600);schedule(b,3600);String text=blocked?"我查看眼前现场。":"我整理衣领。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text,"localObservation",blocked))));var r=new LinkedHashMap<>(result(blocked?"现场烟雾遮挡，未能看清。":"你理好衣领。",2,List.of()));if(blocked)r.put("status","BLOCKED");return response(r);}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("no-extra-sight-"+blocked,text));assertEquals("COMPLETED",done.get("status"));var saved=json.readTree(payload("no-extra-sight-"+blocked));assertTrue(saved.path("serverObservationBindings").isEmpty());assertEquals(0,saved.path("events").findValuesAsText("kind").stream().filter("OBSERVE"::equals).count());
 }
 @Test void committedVisibleProcessRemainsHistoryInsteadOfInventingContinuingWork()throws Exception{
  acceptedWork(2,List.of());schedule(a,3600);schedule(b,3600);waitFor(3);
  var done=operations.create(nextInput("completed-process-is-history","等待3秒"));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("甲的原工作实际完成"));
  var observed=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,player,"observed:"+scene+":"+a));
  assertEquals("",observed.path("activity").asText());assertTrue(observed.path("lastObservedProcess").asText().contains("甲的原工作实际完成"));assertTrue(observed.path("activityHistorical").asBoolean());
  assertTrue(knownState.current(sid,player,scene).stream().filter(n->a.equals(n.get("subject")) && "activity".equals(n.get("field"))).allMatch(n->"".equals(n.get("value"))));
  assertEquals(1,json.readTree(payload("completed-process-is-history")).path("events").findValuesAsText("text").stream().filter(t->t.contains("甲的原工作实际完成")).count());verify(model,never()).chatSubForJson(anyList());
 }
 @Test void executionSnapshotUsesOnlyThisActorsDeliveredChangesAndMarksTheirTime()throws Exception{
  schedule(a,3600);schedule(b,3600);
  for(String owner:List.of(player,b))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'已交付',?,'TURN_EVENT',?,?, '',0)",sid,owner,"snapshot-evidence-"+owner,json.writeValueAsString(Map.of("kind","ACTION_RESULT","actorId",a,"text",owner.equals(player)?"甲已经搁笔直身。":"REMOTE_PRIVATE_UNSEEN","endSeconds",0)),a,scene);
  // Speech has a separate history budget; it must not evict the last physical change.
  for(int i=0;i<15;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'对话',?,'TURN_EVENT',?,?, '',0)",sid,player,"snapshot-speech-"+i,json.writeValueAsString(Map.of(i%2==0?"kind":"recordType","UTTERANCE","actorId",a,"text","日常交谈第"+i+"句","endSeconds",0)),a,scene);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'snapshot-future','未来边界',?,'TURN_EVENT',?,?, '',0)",sid,player,json.writeValueAsString(Map.of("kind","ACTION_RESULT","actorId",a,"text","FUTURE_PERCEPTION","endSeconds",100)),a,scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我查看碑旁动静。"))));
   var input=json.readTree(m.get(1).get("content"));assertEquals(0,input.path("snapshotAtSeconds").asLong(-1));assertEquals(1,input.path("recentPerceivedChanges").size());assertTrue(input.path("recentPerceivedChanges").toString().contains("甲已经搁笔直身"));assertFalse(input.toString().contains("REMOTE_PRIVATE_UNSEEN"));assertFalse(input.toString().contains("FUTURE_PERCEPTION"));assertTrue(input.path("observationTimeContract").asText().contains("不是动作结束时的预言"));return response(result("你留意碑旁动静。",2,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));
  }).when(model).chatForJson(anyList());assertEquals("COMPLETED",operations.create(nextInput("dated-execution-snapshot","我查看碑旁动静。")).get("status"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"hidden","elsewhere","asleep-observer","away-observer"})
 void addingPlayerToNpcScansDoesNotGrantHiddenRemoteOrUnconsciousSight(String mode)throws Exception{
  var projection=(PlayerSceneProjectionService)org.springframework.test.util.ReflectionTestUtils.getField(operations,"playerProjection");var here=scenes.findById(scene).orElseThrow();projection.enter(sid,here,a,0);
  assertTrue(projection.bodies(sid,here,a).stream().anyMatch(n->player.equals(n.get("id"))));
  if(mode.equals("elsewhere")){String remote=scene+"_remote";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处','[]','远处',1,0)",remote,sid);jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE player_id=?",remote,pid);}
  else if(mode.equals("away-observer")){jdbc.update("UPDATE game_scene SET present_gods=JSON_ARRAY(?) WHERE scene_id=?",b,scene);here=scenes.findById(scene).orElseThrow();}
  else{String role=mode.equals("hidden")?player:a;var body=mode.equals("hidden")?Map.of("hidden",true,"position","隐藏的具体位置"):Map.of("awake",false);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'视野变化',?,'SCENE_VIEW','',?,'',0)",sid,role,"view:"+scene,json.writeValueAsString(body),scene);}
  projection.enter(sid,here,a,5);var visible=projection.bodies(sid,here,a);
  if(Set.of("asleep-observer","away-observer").contains(mode))assertTrue(visible.stream().filter(n->player.equals(n.get("id"))).allMatch(n->((Number)n.get("observedAtSeconds")).longValue()==0));else assertTrue(visible.stream().noneMatch(n->player.equals(n.get("id"))));
  assertFalse(visible.toString().contains("隐藏的具体位置"));verifyNoInteractions(model);
 }
 @Test void localObservationCommitsThroughTheWholeOperationWithCanonicalIdentity()throws Exception{
  String text="我看看眼前人物。";doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));return response(result("旅人查看了眼前人物。",2,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE","sceneId","copy-typo"))));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("local-observation",text));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("旅人查看了眼前人物"));assertFalse(payload("local-observation").contains("observation is not at"));assertEquals(2L,done.get("storySeconds"));
  record("offline-observation-bound-to-current-scene",done);
 }

 @AfterEach void cleanup(){
  jdbc.update("DELETE FROM llm_operation_usage WHERE session_id=?",sid);
  for(String table:List.of("session_checkpoint_archive","game_goal_rule","game_operation","game_runtime","session_checkpoint","npc_decision_log","npc_memory","npc_goal","player_memory","player_objective","story_summary","interaction_log","world_book","god_relationship","scene_connection","session_npc_alias","npc_plan","world_clock","role_knowledge"))jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",sid);
  jdbc.update("DELETE FROM player_profile WHERE player_id=?",pid);jdbc.update("DELETE FROM game_session WHERE session_id=?",sid);jdbc.update("DELETE FROM game_scene WHERE session_id=?",sid);jdbc.update("DELETE FROM god_profile WHERE session_id=?",sid);
 }
 @Test void ordinaryVisibleProcessDoesNotFailOrClaimAnUnidentifiedGoalComplete()throws Exception{
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);return response(a.equals(json.readTree(messages.get(1).get("content")).path("id").asText())?choice("自己的私事","","","按着短刀查看绳结"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("你结算"))return response(Map.of("process","甲按着短刀查看绳结。","durationSeconds",3,"effects",List.of(),"status","COMPLETED","observations",List.of(Map.of("channel","VISUAL","text","甲低头查看绳结，手按着短刀。","action","","status","COMPLETED"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("ordinary-visible-process"));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("甲低头查看绳结，手按着短刀。"));
  var saved=json.readTree(payload("ordinary-visible-process"));for(var event:saved.path("events"))if(event.path("text").asText().contains("手按着短刀")){assertFalse(event.has("status"));assertFalse(event.has("attempt"));}
 }
 @Test void rejectedAuthorizationKeepsExactCandidateWithoutExecutingUnknownTarget()throws Exception{
  var bad=Map.of("steps",List.of(Map.of("type","WAIT","text","等待5秒","seconds",5,"targetId","unknown")));
  doReturn(response(bad)).when(model).chatForJson(anyList());var result=operations.create(nextInput("authorization-evidence","等待5秒"));assertEquals("PARTIAL",result.get("status"));assertEquals(0L,((Number)result.get("storySeconds")).longValue());
  var saved=json.readTree(payload("authorization-evidence"));assertEquals(json.valueToTree(bad),json.readTree(saved.path("authorizationAttempts").get(0).path("rawResponse").asText()));assertFalse(story(result).contains("unknown"));assertTrue(saved.path("authorization").isMissingNode());
 }
 List<JsonNode> treatyRecords(String owner){return jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY' ORDER BY knowledge_id",String.class,sid,owner).stream().map(text->{try{return json.readTree(text);}catch(Exception e){throw new RuntimeException(e);}}).toList();}
 ConversationOperationService.Request treatySpeech(String id,String target,String speech){var r=nextInput(id,speech);return new ConversationOperationService.Request(id,sid,pid,r.sceneId(),speech,new ActionRequest.DialogueInput(target,speech),r.branchEpoch(),r.expectedRevision());}
 @Test void capturedRealAllianceReplyAfterWorkCommitsConsentExactlyOnce()throws Exception{
  schedule(b,3600);doAnswer(call->response(choice("","","",""))).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(treatySpeech("captured-alliance-offer",a,"我提议与你结盟，期限三分钟。")).get("status"));
  String speech="是，我应你。三分钟内我与你结盟，各自行事、互不交手——我不动你，你也别来扰我翻晒这些药。这话说到这儿，不必再问了。";var calls=new AtomicInteger();
  doAnswer(call->{boolean first=calls.getAndIncrement()==0;var d=choice("先做完手头工作再回答",speech,player,first?"整理自己的衣物":"");d.put("speech_timing",first?"AFTER_ACTION":"BEFORE_ACTION");d.put("next_think_seconds",3600);return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("甲整理完自己的衣物。",2,List.of()):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(treatySpeech("captured-alliance-answer",a,"请按原提议自行决定。"));assertEquals("COMPLETED",done.get("status"));
  var pact=treatyRecords(player).get(0);assertEquals("ACTIVE",pact.path("status").asText());assertEquals(180,pact.path("dueSeconds").asLong()-pact.path("acceptedSeconds").asLong());assertEquals(treatyRecords(player),treatyRecords(a));
  assertEquals(1,json.readTree(payload("captured-alliance-answer")).path("events").findValuesAsText("speech").stream().filter(speech::equals).count());
 }
 @Test void bilateralAlliancePersistsAcrossTurnsAndRewindRestoresPendingConsent()throws Exception{
  schedule(a,3600);schedule(b,3600);var accept=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(a.equals(self.path("id").asText()) && accept.getAndSet(false)){assertTrue(self.path("ownKnowledge").asText().contains("待回应"));return response(choice("我独立决定接受这次结盟","我同意与你结盟。",player,""));}return response(choice("保留自己的立场","","",""));}).when(model).chatSubForJson(anyList());
  var offer=operations.create(treatySpeech("treaty-offer",a,"我提议与你结盟，期限三分钟。"));assertEquals("COMPLETED",offer.get("status"));assertEquals(1,treatyRecords(player).size());assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());assertEquals(treatyRecords(player),treatyRecords(a));assertTrue(treatyRecords(b).isEmpty());
  accept.set(true);assertEquals("COMPLETED",operations.create(treatySpeech("treaty-accept",a,"你是否愿意接受刚才的结盟提议？")).get("status"));var pact=treatyRecords(player).get(0);assertEquals("ACTIVE",pact.path("status").asText());assertEquals(180,pact.path("dueSeconds").asLong()-pact.path("acceptedSeconds").asLong());assertTrue(knowledge.list(sid,player).toString().contains("盟约生效"));assertFalse(knowledge.privateContext(sid,b).contains("盟约生效"));
  var ended=operations.create(treatySpeech("treaty-end",a,"我解除与你的盟约。"));assertEquals("COMPLETED",ended.get("status"));assertEquals("ENDED",treatyRecords(a).get(0).path("status").asText());
  checkpoints.restore(sid,2);assertEquals("ACTIVE",treatyRecords(player).get(0).path("status").asText());checkpoints.restore(sid,1);assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());checkpoints.restore(sid,0);assertTrue(treatyRecords(player).isEmpty());
 }
 @Test void actualNpcAllianceInvitationPausesEvenWhenTheModelOmitsItsDecisionFlag()throws Exception{
  schedule(a,3600);schedule(b,3600);var offered=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String id=json.readTree(m.get(1).get("content")).path("id").asText();if(a.equals(id) && !offered.getAndSet(true))return response(choice("我只提出邀请，尚不替对方接受","我提议与你结盟。",player,""));return response(choice("继续自己的判断","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId",a))));return response(Map.of("kind","DONE","story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(storyRequest("npc-treaty-offer","你好。"));assertEquals("COMPLETED",done.get("status"));assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());var progress=(Map<?,?>)((Map<?,?>)done.get("ruleSnapshot")).get("storyProgress");assertEquals(true,progress.get("requiresInput"));assertEquals("ALLIANCE",progress.get("decisionType"));assertFalse(story(done).contains("你说：“我同意"));
  assertEquals("COMPLETED",operations.create(treatySpeech("human-treaty-accept",a,"我同意与你结盟。")).get("status"));assertEquals("ACTIVE",treatyRecords(player).get(0).path("status").asText());
 }
 @Test void actualAttackChangesTheAllianceBeforeTheNpcIndependentlyReacts()throws Exception{
  schedule(a,3600);schedule(b,3600);var accepted=new java.util.concurrent.atomic.AtomicBoolean(false);var sawBreach=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));if(!accepted.getAndSet(true))return response(choice("我独立决定接受","我同意与你结盟。",player,""));sawBreach.set(self.path("ownKnowledge").asText().contains("交手破盟"));return response(choice("我自行决定暂时后退，不被系统强制效忠或敌对","","",""));}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(treatySpeech("attack-pact-offer",a,"我提议与你结盟。" )).get("status"));assertEquals("ACTIVE",treatyRecords(player).get(0).path("status").asText());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","与甲交手","contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU")))));if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));var c=facts.path("serverContest");return response(result("你与工匠甲实际交手。",3,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",c.path("outcome").asText(),"fingerprint",c.path("fingerprint").asText()))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("attack-pact-breach","与甲交手" )).get("status"));assertEquals("BROKEN",treatyRecords(player).get(0).path("status").asText());assertTrue(sawBreach.get());assertEquals(75,actors.findById(a).orElseThrow().getBaseGui());checkpoints.restore(sid,1);assertEquals("ACTIVE",treatyRecords(player).get(0).path("status").asText());
 }
 @Test void changedAllianceTermNeedsAgreementBeforeNaturalExplicitAcceptanceCanActivateIt()throws Exception{
  schedule(a,3600);schedule(b,3600);var replies=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));return response(choice("按自己的意愿回应",replies.getAndIncrement()==0?"我同意与你结盟。期限改为十秒。":"你的结盟提议，我同意。",player,""));}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(treatySpeech("changed-term-offer",a,"我提议与你结盟，期限三分钟。" )).get("status"));assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());
  assertEquals("COMPLETED",operations.create(treatySpeech("changed-term-confirm",a,"原提议仍是三分钟，请自行决定接受还是拒绝。" )).get("status"));var pact=treatyRecords(player).get(0);assertEquals("ACTIVE",pact.path("status").asText());assertEquals(180,pact.path("dueSeconds").asLong()-pact.path("acceptedSeconds").asLong());assertEquals(treatyRecords(player),treatyRecords(a));
 }
 @Test void realShortAllianceReplyUsesOnlyTheActualQuestionInTheFrozenNpcStimulus()throws Exception{
  schedule(a,3600);schedule(b,3600);var replies=new AtomicInteger();String question="刚才你说可以互不交手。我想确认：你是否愿意按原提议，在三分钟内与我结盟、各自行事？接受、拒绝或另有打算都请直接告诉我。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));if(replies.getAndIncrement()==0)return response(choice("暂时只同意不动手","互不交手，各自行事——这个我可以应。",player,""));if(replies.get()>2)return response(choice("我自行决定听完解除后不再追加话语","","",""));assertTrue(self.path("pendingStimuli").toString().contains("你是否愿意按原提议"));return response(choice("我自行决定接受原提议","是，我应你。三分钟内互不交手、各自行事，我不主动动你，你也别来扰我晒药。",player,""));}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(treatySpeech("short-assent-offer",a,"我提议与你结盟，期限三分钟。" )).get("status"));assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());
  assertEquals("COMPLETED",operations.create(treatySpeech("short-assent-question",a,question)).get("status"));var pact=treatyRecords(player).get(0);assertEquals("ACTIVE",pact.path("status").asText());assertEquals(180,pact.path("dueSeconds").asLong()-pact.path("acceptedSeconds").asLong());assertTrue(pact.path("events").get(1).path("replyToEventRef").asText().startsWith("op:short-assent-question:"));
  assertEquals("COMPLETED",operations.create(treatySpeech("short-assent-end",a,"我解除与你的盟约。" )).get("status"));assertEquals("ENDED",treatyRecords(player).get(0).path("status").asText());checkpoints.restore(sid,1);assertEquals("PROPOSED",treatyRecords(player).get(0).path("status").asText());
 }
 ConversationOperationService.Request talk(String id){return new ConversationOperationService.Request(id,sid,pid,scene,"能借我短刀吗？",new ActionRequest.DialogueInput(a,"能借我短刀吗？"),0L,0L);}
 String story(Map<String,Object> r){return ((List<Map<String,Object>>)r.get("lines")).get(0).get("speech").toString();}
 String payload(String id){return jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,id);}
 void record(String name,Map<String,Object> result)throws Exception{
  String directory=System.getProperty("game.verification.output");if(directory==null)return;
  var target=java.nio.file.Path.of(directory);java.nio.file.Files.createDirectories(target);
  var publicResult=new LinkedHashMap<String,Object>();publicResult.put("scenario",name);publicResult.put("model","controlled mock, not real API");
  for(String field:List.of("status","playerInput","storySeconds","lines","ruleSnapshot","round","waitResults","scene"))publicResult.put(field,result.get(field));
  java.nio.file.Files.writeString(target.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(publicResult));
 }
 Map<String,Object> departModel(boolean secret)throws Exception{
  String destination=scene+"_unseen";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'未知密室','[]','密室内无人。',1,0)",destination,sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','东侧出口')",sid,scene,destination);
  AtomicInteger moves=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String input=m.get(1).get("content");rolePrompts.add(input);var self=json.readTree(input);
   var answer=choice("按照自己的计划","","",self.path("id").asText().equals(a) && moves.getAndIncrement()==0?"离开此地":"");answer.put("next_think_seconds",3600);return response(answer);
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","查看眼前的人物"))));
   if(system.contains("你结算")){var input=json.readTree(m.get(1).get("content"));if(input.path("actorId").asText().equals(a)){
     var execution=new LinkedHashMap<String,Object>(result("甲穿过东侧出口，抵达未知密室。",3,List.of(Map.of("kind","MOVE","actorId",a,"fromSceneId",scene,"toSceneId",destination))));execution.put("visibility",secret?"PRIVATE":"PUBLIC");return response(execution);
    }return response(result("你查看了眼前可见的人物。",2,List.of(Map.of("kind","OBSERVE","actorId",player,"sceneId",scene,"scope","VISIBLE_SCENE"))));
   }return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  return operations.create(new ConversationOperationService.Request("departure",sid,pid,scene,"你继续忙吧",new ActionRequest.DialogueInput(a,"你继续忙吧"),0L,0L));
 }
 @Test void publicDepartureUpdatesRosterButDoesNotRevealDestination()throws Exception{
  var first=departModel(false);assertEquals("COMPLETED",first.get("status"));record("亲见离场不揭露目的地",first);
  assertTrue(story(first).contains("离开了此地"));assertFalse(first.toString().contains("未知密室"));assertFalse(first.get("ruleSnapshot").toString().contains(scene+"_unseen"));
  assertFalse(((Map<?,?>)first.get("scene")).get("presentGods").toString().contains(a));
  String other=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND source_ref LIKE 'opactual:%'",String.class,sid,b);
  assertFalse(other.contains("未知密室"));assertFalse(other.contains("toSceneId"));
  assertTrue(knownState.current(sid,player,scene).stream().noneMatch(f->a.equals(f.get("subject")) && Set.of("position","awake","bound","activity").contains(f.get("field"))));
 }
 @Test void privateDepartureRemainsLastSeenUntilPlayerActuallyLooks()throws Exception{
  var first=departModel(true);assertEquals("COMPLETED",first.get("status"));record("未感知离场保留最近所见",first);
  assertFalse(story(first).contains("未知密室"));assertTrue(((Map<?,?>)first.get("scene")).get("presentGods").toString().contains(a));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND source_ref LIKE 'opactual:%'",Integer.class,sid,b));
  int previousCalls=rolePrompts.size();var called=operations.create(new ConversationOperationService.Request("call_absent",sid,pid,scene,"你还在吗",new ActionRequest.DialogueInput(a,"你还在吗"),0L,((Number)first.get("revision")).longValue()));record("按旧所见呼唤也消耗真实时间",called);
  assertEquals("COMPLETED",called.get("status"));assertTrue(((Number)called.get("storySeconds")).longValue()>((Number)first.get("storySeconds")).longValue());assertTrue(story(called).contains("没有听到对方的回应"));assertEquals(previousCalls,rolePrompts.size(),"unheard call must not wake absent role or reroll a bystander");assertFalse(called.toString().contains("未知密室"));
  var looked=operations.create(new ConversationOperationService.Request("look",sid,pid,scene,"查看眼前的人物",null,0L,((Number)called.get("revision")).longValue()));record("实际观察后更新人物面板",looked);
  assertEquals("COMPLETED",looked.get("status"));assertFalse(((Map<?,?>)looked.get("scene")).get("presentGods").toString().contains(a));assertFalse(looked.toString().contains("未知密室"));
  assertTrue(knownState.current(sid,player,scene).stream().anyMatch(f->a.equals(f.get("subject")) && "presence".equals(f.get("field")) && "NOT_SEEN".equals(f.get("value"))));
 }
 @Test void actualRescueUpdatesTheRescuerAndWitnessesObservedBody()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','body','{\"awake\":true,\"bound\":true,\"position\":\"石柱旁\",\"activity\":\"被绑住\"}','BODY_STATE','',?,'',0)",sid,a,scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","解开甲的束缚"))));
   if(system.contains("你结算"))return response(result("你解开了甲的束缚。",4,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("bound",true),"after",Map.of("bound",false)))));return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var answer=operations.create(new ConversationOperationService.Request("rescue_body",sid,pid,scene,"解开甲的束缚",null,0L,0L));record("救援后人物状态与正文一致",answer);assertEquals("COMPLETED",answer.get("status"));
  var body=((List<Map<String,Object>>)((Map<?,?>)answer.get("scene")).get("currentPhysicalStates")).stream().filter(v->a.equals(v.get("id"))).findFirst().orElseThrow();assertEquals(false,body.get("bound"));assertEquals(4L,((Number)body.get("observedAtSeconds")).longValue());
  var other=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,b,"observed:"+scene+":"+a));assertFalse(other.path("bound").asBoolean());
 }
 @Test void refusalIsAContinuousChapterAndNeverCreatesARescueOrSecondBorrow()throws Exception{
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);String input=m.get(1).get("content");rolePrompts.add(input);
   return response(json.readTree(input).path("id").asText().equals(a)?choice("还要自己用","不借。",player,""):choice("观察现场","","",""));}).when(model).chatSubForJson(anyList());
  var r=operations.create(talk("refusal"));record("拒借",r);assertEquals("COMPLETED",r.get("status"));assertTrue(story(r).contains("不借。"));assertFalse(story(r).contains("向守望者乙借"));
  assertEquals(List.of("短刀"),actors.findById(a).orElseThrow().getInventory());assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
  assertEquals(3L,((Number)r.get("storySeconds")).longValue());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
  assertFalse(r.toString().contains("还要自己用"));
 }
 @Test void sameOperationIsIdempotentAndDifferentContentConflicts(){
  var request=talk("same");var first=operations.create(request);int calls=rolePrompts.size();long seconds=((Number)first.get("storySeconds")).longValue();
  var again=operations.create(request);assertEquals(first.get("logId"),again.get("logId"));assertEquals(seconds,((Number)again.get("storySeconds")).longValue());assertEquals(calls,rolePrompts.size());
  assertThrows(RuntimeException.class,()->operations.create(new ConversationOperationService.Request("same",sid,pid,scene,"别借了",new ActionRequest.DialogueInput(a,"别借了"),0L,0L)));
 }
 @Test void independentActionsUseOneTimeAxisAndFrozenCohortContexts()throws Exception{
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));String role=self.path("id").asText();
   if(role.equals(b))assertFalse(m.get(1).get("content").contains("新说出的甲的话"));return response(choice("私有"+role,role.equals(a)?"新说出的甲的话":"",player,"整理自己的衣角"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(m.get(0).get("content").contains("你结算")){var data=json.readTree(m.get(1).get("content"));String role=data.path("actorId").asText();return response(result(role.equals(a)?"甲整理衣角完毕。":"乙整理衣角完毕。",role.equals(a)?4:8,List.of()));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(talk("parallel"));record("并行行动",r);assertEquals("COMPLETED",r.get("status"));assertEquals(10L,((Number)r.get("storySeconds")).longValue(),"2 second question + max(2+4,8), not sum");assertTrue(story(r).contains("乙整理衣角完毕。"));
 }
 @Test void savedChoiceIsReusedAfterExecutionApiFailure()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);rolePrompts.add(m.get(1).get("content"));var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("固定决定","不借。",player,"整理衣角"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  AtomicInteger attempts=new AtomicInteger();doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){if(attempts.getAndIncrement()==0)throw new LLMApiException("mock timeout",false);return response(result("甲整理好了衣角。",4,List.of()));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("retry"));record("执行失败后已保存部分",partial);assertEquals("PARTIAL",partial.get("status"));assertTrue(story(partial).contains("不借"),"complete BEFORE_ACTION speech survives a later technical failure");int decisions=rolePrompts.size();
  var completed=operations.recover(sid,pid,"retry",0);record("同一操作续接",completed);assertEquals("COMPLETED",completed.get("status"));assertEquals(decisions,rolePrompts.size());assertTrue(story(completed).contains("不借。"));assertEquals(partial.get("logId"),completed.get("logId"));
 }
 @Test void missingRemainingWorkRepairCannotRewriteProcessOrEffects()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("固定选择","","","整理衣物"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("你结算")){
    var candidate=new LinkedHashMap<String,Object>();candidate.put("process",m.size()>2?"偷偷改成已经完成":"甲继续整理衣物。");candidate.put("durationSeconds",m.size()>2?99:4);candidate.put("effects",m.size()>2?List.of(Map.of("kind","INVENTED_RESOURCE")):List.of());candidate.put("status",m.size()>2?"COMPLETED":"ONGOING");if(m.size()>2)candidate.put("remainingSeconds",8);return response(candidate);
   }return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var actual=operations.create(talk("bad-local-repair"));assertEquals("COMPLETED",actual.get("status"));assertFalse(story(actual).contains("偷偷改成"));assertEquals(1,json.readTree(payload("bad-local-repair")).path("repairAttempts").asInt());var saved=json.readTree(payload("bad-local-repair"));assertEquals(2,saved.path("candidateAttempts").findValuesAsText("rawResponse").stream().filter(v->v.contains("甲继续整理衣物") || v.contains("偷偷改成")).count());assertTrue(saved.path("executions").toString().contains("甲继续整理衣物"));assertFalse(saved.path("executions").toString().contains("偷偷改成"));assertFalse(saved.path("executions").toString().contains("INVENTED_RESOURCE"));assertTrue(saved.path("executions").findValuesAsText("status").contains("ONGOING"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"missing","zero","text"})
 void numericRemainingWorkRepairRejectsAbsentZeroAndTextValues(String invalid)throws Exception{
  schedule(b,3600);doAnswer(call->response(choice("固定原选择","","","整理衣物"))).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!m.get(0).get("content").contains("你结算"))return response(Map.of("story","","eventRefs",List.of()));if(m.size()>2)return response(invalid.equals("missing")?Map.of():Map.of("remainingSeconds",invalid.equals("zero")?0:"十秒"));var n=new LinkedHashMap<>(result("甲继续整理衣物。",4,List.of()));n.put("status","ONGOING");return response(n);}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("invalid-numeric-repair-"+invalid));assertEquals("PARTIAL",partial.get("status"));assertFalse(story(partial).contains("甲继续整理衣物"));assertEquals(1,json.readTree(payload("invalid-numeric-repair-"+invalid)).path("repairAttempts").asInt());
 }
 @Test void stalePlacementRevalidatesWorldOnceWithoutRerollingNpcChoice()throws Exception{
  String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();placements.place(sid,actors.findById(a).orElseThrow(),scene,ref,"","左手");schedule(b,3600);AtomicInteger choices=new AtomicInteger(),worldCalls=new AtomicInteger();
  doAnswer(call->{choices.incrementAndGet();return response(choice("保留原决定","不借。",player,"把短刀放入腰带"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){int n=worldCalls.getAndIncrement();return response(result("甲将短刀放入腰带。",2,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before",n==0?"已经失效的位置":"左手","after","腰带"))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("revalidate-placement"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get());assertEquals(2,worldCalls.get());assertEquals("腰带",placements.position(sid,a,ref));var saved=json.readTree(payload("revalidate-placement"));assertEquals(1,saved.path("worldRevalidationAttempts").asInt());assertEquals(1,saved.path("rejectedWorldBatches").size());assertTrue(saved.path("rejectedWorldBatches").toString().contains("已经失效的位置"));int words=0,effects=0;for(var e:saved.path("events")){if("不借。".equals(e.path("speech").asText()))words++;if("甲将短刀放入腰带。".equals(e.path("text").asText()))effects++;}assertEquals(1,words);assertEquals(1,effects);
 }
 @Test void repeatedInvalidPlacementStopsInsteadOfRetryingForever()throws Exception{
  String ref=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();schedule(b,3600);AtomicInteger worldCalls=new AtomicInteger();doAnswer(call->response(choice("自己的选择","","","把短刀放入腰带"))).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){worldCalls.incrementAndGet();return response(result("尚未实际发生。",2,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"before","并不存在的位置","after","腰带"))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk("bounded-revalidation"));assertEquals("PARTIAL",partial.get("status"));assertEquals(2,worldCalls.get());assertEquals("",placements.position(sid,a,ref));assertEquals(2,json.readTree(payload("bounded-revalidation")).path("rejectedWorldBatches").size());assertFalse(story(partial).contains("尚未实际发生"));
 }
 @Test void separateActorsHaveIndependentPlacementRevalidationAllowances()throws Exception{
  jdbc.update("UPDATE god_profile SET inventory=? WHERE god_id=?",jsonArray("木棒"),b);String aRef=itemState.owned(sid,actors.findById(a).orElseThrow()).get(0).ref(),bRef=itemState.owned(sid,actors.findById(b).orElseThrow()).get(0).ref();var calls=new ConcurrentHashMap<String,AtomicInteger>();AtomicInteger choices=new AtomicInteger();
  doAnswer(call->{choices.incrementAndGet();return response(choice("自己的原选择","","","把自己的工具放好"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){var facts=json.readTree(m.get(1).get("content"));String role=facts.path("actorId").asText();int n=calls.computeIfAbsent(role,key->new AtomicInteger()).getAndIncrement();boolean valid=n>=1;return response(result("完成自己的放置。",1,List.of(Map.of("kind","ITEM_POSITION","itemRef",role.equals(a)?aRef:bRef,"before",valid?"":"无效旧位置","after","腰带"))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("independent-revalidation"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,choices.get());assertEquals(2,calls.get(a).get());assertEquals(2,calls.get(b).get());assertEquals("腰带",placements.position(sid,a,aRef));assertEquals("腰带",placements.position(sid,b,bRef));var saved=json.readTree(payload("independent-revalidation"));assertEquals(2,saved.path("worldRevalidationAttempts").asInt());assertEquals(2,saved.path("worldRevalidationByJob").size());int count=0;for(var e:saved.path("events"))if("完成自己的放置。".equals(e.path("text").asText()))count++;assertEquals(2,count);
 }
 @Test void missingRemainingWorkCanBeRepairedOnceWithoutRerollingChoice()throws Exception{
  AtomicInteger decisions=new AtomicInteger();
  doAnswer(call->{decisions.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("固定选择","","","整理衣物"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("你结算")){
    var candidate=new LinkedHashMap<String,Object>();candidate.put("process","甲继续整理衣物。");candidate.put("observations",List.of(Map.of("channel","VISUAL","text","甲继续整理衣物。","action","整理衣物")));candidate.put("durationSeconds",4);candidate.put("effects",List.of());candidate.put("status","ONGOING");if(m.size()>2)candidate.put("remainingSeconds",8);return response(candidate);
   }return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var actual=operations.create(talk("local-repair"));assertEquals("COMPLETED",actual.get("status"));assertTrue(story(actual).contains("甲继续整理衣物。"));assertEquals(2,decisions.get());assertEquals(1,json.readTree(payload("local-repair")).path("repairAttempts").asInt());
 }
 @Test void invalidEffectRollsBackAllEffectsAndSpeechThenRecoveryReusesDecision(){
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);rolePrompts.add(m.get(1).get("content"));var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("","已经处理。",player,"整理衣角"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("甲整理了衣角。",4,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("activity",""),"after",Map.of("activity","已整理")),Map.of("kind","INVENTED_RESOURCE"))):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(talk("invalid"));assertEquals("PARTIAL",r.get("status"));assertEquals("",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));assertTrue(story(r).contains("已经处理"),"a saved utterance is a claim, not proof the invalid effect occurred");
  assertTrue(payload("invalid").contains("已经处理"));assertThrows(RuntimeException.class,()->oldActions.processActionWithDb(new ActionRequest(pid,scene,"观察",null),sid));
 }
 @Test void actualLoanTransfersTheExactInstanceAndRetainsLegalOwner()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("要还给我","拿去，用完还我。",player,"将自己持有的短刀借给玩家"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){var data=json.readTree(m.get(1).get("content"));String ref=data.path("resources").path(a).path("items").get(0).path("ref").asText();return response(result("甲将短刀交到你手中，约定用完归还。",3,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(talk("loan"));record("实际借出",r);assertEquals("COMPLETED",r.get("status"));assertEquals(List.of("短刀"),actors.findById(player).orElseThrow().getInventory());assertTrue(actors.findById(a).orElseThrow().getInventory().isEmpty());
  String held=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE'",String.class,sid,player);assertTrue(held.contains("LEND"));assertTrue(held.contains(a));
  checkpoints.restore(sid,0);assertEquals(List.of("短刀"),actors.findById(a).orElseThrow().getInventory());assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
 }
 @Test void freeActionUsesOnlyPlayerAuthorizationAndPersistsActualFacilityResult(){
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","检查绳结"))));if(system.contains("你结算"))return response(result("你检查绳结，确认仍未解开。",3,List.of(Map.of("kind","FACILITY","objectName","绳结","before","","after","仍未解开"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("free",sid,pid,scene,"检查绳结",null,0L,0L));assertEquals("COMPLETED",r.get("status"));assertTrue(story(r).contains("仍未解开"));assertEquals(3L,r.get("storySeconds"));
  assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",String.class,sid).contains("仍未解开"));
 }
 @Test void cancellationRejectsLateRoleResultsAndDoesNotUndoCompletedPlayerSpeech()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return response(choice("迟到心理","迟到发言",player,""));}).when(model).chatSubForJson(anyList());
  try{var future=executor.submit(()->operations.create(talk("late")));assertTrue(entered.await(15,TimeUnit.SECONDS));var cancelled=operations.cancel(sid,pid,"late",0);assertEquals("CANCELLED",cancelled.get("status"));release.countDown();try{future.get(15,TimeUnit.SECONDS);}catch(ExecutionException expected){}
   assertEquals("CANCELLED",operations.read(sid,pid,"late",0L).get("status"));assertFalse(payload("late").contains("迟到"));assertEquals(2L,operations.read(sid,pid,"late",0L).get("storySeconds"));
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void rewindFencesLateResultsAndRestoresStoryTime()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  doAnswer(call->{entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return response(choice("未来心理","未来发言",player,""));}).when(model).chatSubForJson(anyList());
  try{var future=executor.submit(()->operations.create(talk("future")));assertTrue(entered.await(15,TimeUnit.SECONDS));checkpoints.restore(sid,0);release.countDown();try{future.get(15,TimeUnit.SECONDS);}catch(ExecutionException expected){}
   assertEquals(1L,jdbc.queryForObject("SELECT branch_epoch FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));assertFalse(payload("future").contains("未来心理"));assertFalse(payload("future").contains("未来发言"));
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void waitingUsesAutonomousSchedulesAndDoesNotSumIdleNpcTime(){
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待十秒","seconds",10))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("wait",sid,pid,scene,"等待十秒",null,0L,0L));assertEquals("COMPLETED",r.get("status"));assertEquals(10L,r.get("storySeconds"));assertEquals(2,rolePrompts.size());
 }
 @Test void objectiveCompletionRequiresActualHoldingAndPersonalGoalsDoNotEndStory(){
  jdbc.update("INSERT INTO player_objective(objective_id,player_id,session_id,title,status,progress) VALUES('loan_goal',?,?,'借到短刀','ACTIVE',0)",pid,sid);
  objectiveRules.define(sid,pid,"loan_goal",json.createObjectNode().put("type","ITEM_HELD").put("itemName","短刀"),false);
  objectiveRules.refresh(sid,pid);assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM player_objective WHERE session_id=?",String.class,sid));
  // A reported or promised loan is not an inventory transfer.
  operations.create(talk("promise_only"));assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM player_objective WHERE session_id=?",String.class,sid));
  jdbc.update("UPDATE god_profile SET inventory='[\"短刀\"]' WHERE god_id=?",player);
  objectiveRules.refresh(sid,pid);assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM player_objective WHERE session_id=?",String.class,sid));
  assertEquals("ONGOING",objectives.evaluateEnding(sid,pid,false).get("ending"));
  assertThrows(IllegalArgumentException.class,()->objectiveRules.define(sid,pid,"invented",json.createObjectNode().put("type","ITEM_HELD").put("itemName","凭空宝物"),false));
 }
 @Test void privateExecutionIsNotInPlayerStoryOrSceneProjection(){
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("私人顾虑","","","私下检查已有记号"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){var candidate=new LinkedHashMap<>(result("甲在无人得知的情况下看到了已有记号。",2,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("activity",""),"after",Map.of("activity","私人记号已核对")))));candidate.put("visibility","PRIVATE");return response(candidate);}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(talk("private"));assertEquals("COMPLETED",r.get("status"));assertFalse(r.toString().contains("私人记号"));assertFalse(r.toString().contains("私人顾虑"));
  assertEquals("私人记号已核对",physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  assertTrue(operations.backstage(sid).isEmpty());jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);assertTrue(operations.backstage(sid).toString().contains("私人顾虑"));
 }
 @Test void localMoveMustCommitPositionInsteadOfOnlyDescribingIt()throws Exception{
  AtomicInteger attempts=new AtomicInteger();String input="我向石柱走近两步，双手垂在身侧，不触碰物品。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"localMovement",true))));
   var facts=json.readTree(messages.get(1).get("content"));assertTrue(facts.path("selectedLocalMovement").asBoolean());
   if(attempts.getAndIncrement()==0)return response(result("你走近石柱两步。",2,List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));
   assertEquals("local movement result must record position",facts.path("rejectedResultReason").asText());
   String original=facts.path("resources").path(player).path("body").path("position").asText();
   return response(result("你走近石柱两步。",2,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",original),"after",Map.of("position","石柱前两步处")))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("local-position",input));assertEquals("COMPLETED",done.get("status"));assertEquals(2,attempts.get());assertEquals(2L,done.get("storySeconds"));
  assertEquals("石柱前两步处",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
  assertEquals("石柱前两步处",((Map<?,?>)((Map<?,?>)done.get("scene")).get("playerPhysicalState")).get("position"));
  assertEquals(1,json.readTree(payload("local-position")).path("events").findValuesAsText("text").stream().filter("你走近石柱两步。"::equals).count());
 }
 @Test void serverTimestampEchoCanBeRepairedWithoutLosingTheChosenMove()throws Exception{
  String input="我走到石柱旁。";AtomicInteger attempts=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"localMovement",true))));
   var facts=json.readTree(m.get(1).get("content"));assertFalse(facts.path("resources").path(player).path("body").has("updatedAtTick"));assertEquals(input,facts.path("attempt").asText());
   var change=new LinkedHashMap<String,Object>(Map.of("position","石柱旁"));if(attempts.getAndIncrement()==0)change.put("updatedAtTick",999999);else assertEquals("body timestamp is server owned",facts.path("rejectedResultReason").asText());
   return response(result("你走到石柱旁。",2,List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",change))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("timestamp-echo",input));assertEquals("COMPLETED",done.get("status"));assertEquals(2,attempts.get());assertEquals(2L,done.get("storySeconds"));var body=physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow());assertEquals("石柱旁",body.get("position"));assertNotEquals(999999L,((Number)body.get("updatedAtTick")).longValue());
 }
 @Test void blockedLocalMoveDoesNotForceAPositionChange()throws Exception{
  String input="我试着走近石柱。";String before=physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position").toString();AtomicInteger executions=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"localMovement",true))));
   executions.incrementAndGet();var result=new LinkedHashMap<>(result("你未能挪动，仍在原位。",1,List.of()));result.put("status","BLOCKED");return response(result);
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("blocked-position",input));assertEquals("COMPLETED",done.get("status"));assertEquals(1,executions.get());assertEquals(before,physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
 }
 @Test @org.springframework.transaction.annotation.Transactional void backstageLoadsLargePayloadsWithoutSortingTheirJson()throws Exception{
  jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);
  long original=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
  try{
   jdbc.execute("SET SESSION sort_buffer_size=32768");
   for(int i=1;i<=3;i++){
    String job=a+"|large_"+i;var p=json.createObjectNode().put("operationId","large_"+i).put("playerRole",player).put("logId",i).put("padding","x".repeat(300000));
    p.putObject("decisions").set(job,json.valueToTree(choice("自己的想法","第"+i+"句。",player,"")));p.putArray("spokenJobs").add(job);
    jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload,updated_at) VALUES(?,0,?,'test',?,'COMPLETED',0,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)))",sid,"large_"+i,pid,p.toString(),i);
   }
   var records=operations.backstage(sid);assertEquals(3,records.size());
   assertEquals(List.of(3L,2L,1L),records.stream().map(r->((Number)r.get("logId")).longValue()).toList());
   assertTrue(records.get(0).get("speech").toString().contains("第3句"));assertFalse(records.toString().contains("padding"));
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+original);}
 }
 @Test void nullableRoleOptionsDoNotBlockAnOrdinaryReply()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));var d=choice("继续自己的事务",a.equals(self.path("id").asText())?"我还在这里。":"","","");
   for(String key:List.of("contest","localMovement","work_update","goal_update","player_decision","speech_timing","speech_scope","target_god_id","post_action_intent","next_think_seconds"))d.put(key,null);return response(d);
  }).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("nullable-reply"));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("我还在这里。"));
  var saved=json.readTree(payload("nullable-reply"));assertEquals(0,saved.path("roleRepairAttempts").asInt());assertEquals(0,saved.path("rejectedRoleCandidates").size());
 }
 @Test void nullPostActionIntentDoesNotScheduleAnExtraRoleCall(){
  AtomicInteger choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));
   choices.incrementAndGet();var d=choice("继续自己的事务","","","整理衣物");d.put("post_action_intent",null);d.put("next_think_seconds",3600);return response(d);
  }).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("null-follow-up"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get());assertTrue(story(done).contains("整理了衣角"));
 }
 @Test void absentRoleTimingUsesTheSameBeforeActionDefaultThroughout()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));
   var d=choice("继续自己的事务","先整理一下衣物。",player,"整理衣物");d.remove("speech_timing");d.put("next_think_seconds",3600);return response(d);
  }).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("absent-role-timing"));assertEquals("COMPLETED",done.get("status"));var saved=json.readTree(payload("absent-role-timing"));
  long spokenEnd=-1,actionStart=-1;
  for(var event:saved.path("events"))if(a.equals(event.path("actorId").asText())){
   if("UTTERANCE".equals(event.path("kind").asText()))spokenEnd=event.path("endSeconds").asLong();
   if("ACTION_RESULT".equals(event.path("kind").asText()))actionStart=event.path("startSeconds").asLong();
  }
  assertTrue(spokenEnd>=0);assertTrue(actionStart>=spokenEnd,"default timing must finish speech before the physical attempt starts");
 }
 @Test void nonNullMalformedRoleOptionsAreStillRejected()throws Exception{
  var validate=ConversationOperationService.class.getDeclaredMethod("validateDecision",JsonNode.class);validate.setAccessible(true);
  for(var option:List.of(Map.of("contest","ATTACK"),Map.of("localMovement","true"),Map.of("work_update",Map.of("mode","INVALID")))){
   var d=choice("","","","");d.putAll(option);var thrown=assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(operations,json.valueToTree(d)));assertInstanceOf(IllegalArgumentException.class,thrown.getCause());
  }
 }
 @Test void silentAfterActionWithoutFollowUpDoesNotCreateAnotherReaction(){
  AtomicInteger choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!a.equals(self.path("id").asText()))return response(choice("","","",""));
   choices.incrementAndGet();var d=choice("继续自己的事务","","","整理衣物");d.put("speech_timing","AFTER_ACTION");d.put("next_think_seconds",3600);return response(d);
  }).when(model).chatSubForJson(anyList());
  var done=operations.create(talk("silent-after"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get(),"a timing field without speech or follow-up intent does not authorize extra reaction requests");
  assertTrue(story(done).contains("整理了衣角"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_PLAN','ROLE_PROCESS')",Integer.class,sid,a));
 }
 @Test void deliberateSameAnswerToTwoNewQuestionsIsNotDeduplicatedByText()throws Exception{
  AtomicInteger choices=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("","","",""));choices.incrementAndGet();var d=choice("我仍不愿借出","不借。",player,"");d.put("next_think_seconds",3600);return response(d);}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(talk("refuse-once")).get("status"));
  var rt=jdbc.queryForMap("SELECT revision FROM game_runtime WHERE session_id=?",sid);
  var request=new ConversationOperationService.Request("refuse-again",sid,pid,scene,"能借我短刀吗？",new ActionRequest.DialogueInput(a,"能借我短刀吗？"),0L,((Number)rt.get("revision")).longValue());
  assertEquals("COMPLETED",operations.create(request).get("status"));assertEquals(2,choices.get());
  for(String op:List.of("refuse-once","refuse-again")){int count=0;for(var event:json.readTree(payload(op)).path("events"))if("不借。".equals(event.path("speech").asText()))count++;assertEquals(1,count);}
 }
 @Test void afterActionReplyIntentSurvivesAGestureWithoutBecomingPublicBeforeSpeech()throws Exception{
  String planned="只是核对古碑上的旧刻痕。";AtomicInteger selected=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!self.path("id").asText().equals(a))return response(choice("","","",""));
   if(selected.getAndIncrement()==0){var d=choice("准备回应来意",planned,player,"朝石柱示意一下");d.put("speech_timing","AFTER_ACTION");return response(d);}
   assertEquals("OWN_ACTION_RESULT",self.path("decisionTrigger").asText());boolean found=false;for(var mail:self.path("pendingStimuli")){var event=json.readTree(mail.path("content").asText());if(event.has("pendingSpeech")){assertEquals(planned,event.path("pendingSpeech").path("text").asText());assertEquals(a,event.path("pendingSpeech").path("speakerId").asText());assertEquals(player,event.path("pendingSpeech").path("targetId").asText());assertEquals("NOT_SPOKEN",event.path("pendingSpeech").path("status").asText());found=true;}}assertTrue(found);return response(choice("接续自己的回答",planned,player,""));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(result("甲朝石柱示意了一下。",3,List.of()));assertFalse(m.get(1).get("content").contains("pendingSpeech"));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("gesture-answer"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,selected.get());assertTrue(story(done).contains(planned));assertFalse(done.toString().contains("pendingSpeech"));var events=json.readTree(payload("gesture-answer")).path("events");int utterances=0;long gestureAt=-1,speechAt=-1;for(var event:events){if("ACTION_RESULT".equals(event.path("type").asText()) || "甲朝石柱示意了一下。".equals(event.path("text").asText()))gestureAt=event.path("endSeconds").asLong();if(planned.equals(event.path("speech").asText())){utterances++;speechAt=event.path("startSeconds").asLong();}}assertEquals(1,utterances);assertTrue(speechAt>=gestureAt);
 }
 @Test void npcRespondsToRealResultWithAFreshDecisionAfterAction(){
  AtomicInteger selected=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!self.path("id").asText().equals(a))return response(choice("","","",""));
   if(selected.getAndIncrement()==0){var d=new LinkedHashMap<>(choice("先实际查看","我已经解开。",player,"检查绳结"));d.put("speech_timing","AFTER_ACTION");return response(d);}
   assertEquals("OWN_ACTION_RESULT",self.path("decisionTrigger").asText());assertTrue(m.get(1).get("content").contains("仍然卡住"));return response(choice("检查之后改变判断","仍然卡住，不能直接取下。",player,""));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("甲检查后发现绳结仍然卡住。",3,List.of()):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(talk("after"));assertEquals("COMPLETED",r.get("status"));assertFalse(story(r).contains("我已经解开。"));assertTrue(story(r).contains("仍然卡住，不能直接取下。"));assertEquals(2,selected.get());
 }

 @Test void departureTailRepairsOnceWithoutWritingToTheAbandonedScene()throws Exception{
  String remote=scene+"_boundary";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外无人。',1,0)",remote,sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,scene,remote);
  var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","走到门外看看"))));
   var facts=json.readTree(m.get(1).get("content"));int index=calls.getAndIncrement();
   assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
   if(index>0)assertEquals("arrival boundary permits only observation after movement",facts.path("rejectedResultReason").asText());
   var move=Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",remote);
   return response(result("你来到门外。",2,index==0?List.of(move,Map.of("kind","FACILITY","objectName","石柱","before","","after","已擦净")):List.of(move,Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("departure-repair","走到门外看看"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get());assertEquals(2L,done.get("storySeconds"));
  assertEquals(remote,((Map<?,?>)done.get("scene")).get("sceneId"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_STATE'",Integer.class,sid));
  assertEquals(1,json.readTree(payload("departure-repair")).path("worldRevalidationAttempts").asInt());
 }
 @Test void repeatedDepartureTailStopsAfterOneRepairWithoutCommittingMovement()throws Exception{
  String remote=scene+"_bad_boundary";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外无人。',1,0)",remote,sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,scene,remote);
  var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","走到门外看看"))));
   calls.incrementAndGet();return response(result("不应提交的错误经过。",2,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",remote),Map.of("kind","FACILITY","objectName","石柱","before","","after","已擦净"))));
  }).when(model).chatForJson(anyList());
  var partial=operations.create(nextInput("departure-repeated","走到门外看看"));assertEquals("PARTIAL",partial.get("status"));assertEquals(2,calls.get());assertEquals(0L,partial.get("storySeconds"));
  assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertFalse(story(partial).contains("不应提交"));
 }
 @Test void ordinaryAwakeningOfDeadTargetRepairsToAnHonestBlockedResult()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命','{\"health\":0}','VITAL_STATE','','','',0)",sid,a);
  var calls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","尝试唤醒工匠甲"))));
   var facts=json.readTree(m.get(1).get("content"));assertFalse(facts.path("resources").path(a).path("body").path("alive").asBoolean(true));
   int index=calls.getAndIncrement();if(index>0)assertEquals("ordinary awakening cannot resurrect a dead actor",facts.path("rejectedResultReason").asText());
   var r=new LinkedHashMap<String,Object>(result(index==0?"不应提交的复活。":"你尝试呼唤工匠甲，但未能唤醒他。",2,index==0?List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("awake",false),"after",Map.of("awake",true))):List.of()));
   if(index>0)r.put("status","BLOCKED");return response(r);
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("dead-target-rescue","尝试唤醒工匠甲"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,calls.get());assertFalse(story(done).contains("不应提交"));assertTrue(story(done).contains("未能唤醒"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='BODY_STATE'",Integer.class,sid,a));
 }
 @Test void movementCommitsExistingRouteAndReturnsDestinationScene(){
  String destination=scene+"_east";
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','实际身体','{\"awake\":true,\"bound\":false,\"activity\":\"右脚仍然酸痛\",\"position\":\"石柱旁\"}','BODY_STATE','',?,'',0)",sid,player,scene);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'东侧廊下','[]','东侧廊下没有其他人。',1,0)",destination,sid);
  jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','东侧出口')",sid,scene,destination);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","沿东侧出口前行"))));
   if(system.contains("你结算"))return response(result("你沿现有出口走到东侧廊下。",5,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",destination))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("move",sid,pid,scene,"沿东侧出口前行",null,0L,0L));assertEquals("COMPLETED",r.get("status"));
  assertEquals(destination,((Map<?,?>)r.get("scene")).get("sceneId"));assertEquals(5L,r.get("storySeconds"));assertEquals("右脚仍然酸痛",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(destination).orElseThrow()).get("activity"));
  checkpoints.restore(sid,0);assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
 }
 @Test void tenNewOperationsKeepVersionsAndRewindRestoresSavedSeconds(){
  long revision=0;
  for(int n=1;n<=10;n++){var r=operations.create(new ConversationOperationService.Request("turn_"+n,sid,pid,scene,"能借我短刀吗？",new ActionRequest.DialogueInput(a,"能借我短刀吗？"),0L,revision));assertEquals("COMPLETED",r.get("status"));revision=((Number)r.get("revision")).longValue();assertEquals(2L*n,r.get("storySeconds"));}
  assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));checkpoints.restore(sid,5);
  assertEquals(10L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
 }

 @Test void asynchronousSubmissionCanBeQueriedAndCancelledWhileModelIsRunning()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return response(choice("迟到内心","迟到原话",player,""));}).when(model).chatSubForJson(anyList());
  try{var accepted=operations.submit(talk("async"));assertEquals("RUNNING",accepted.get("status"));assertTrue(entered.await(10,TimeUnit.SECONDS));
   assertEquals("RUNNING",operations.read(sid,pid,"async",0L).get("status"));operations.cancel(sid,pid,"async",0);release.countDown();
   long deadline=System.nanoTime()+10_000_000_000L;while(!operations.workersIdle() && System.nanoTime()<deadline)Thread.sleep(20);
   assertTrue(operations.workersIdle());assertEquals("CANCELLED",operations.read(sid,pid,"async",0L).get("status"));assertFalse(payload("async").contains("迟到"));
  }finally{release.countDown();}
 }
 Map<String,Object> awaitAsync(String id)throws Exception{
  long deadline=System.nanoTime()+15_000_000_000L;while(!operations.workersIdle() && System.nanoTime()<deadline)Thread.sleep(20);assertTrue(operations.workersIdle());return operations.read(sid,pid,id,0L);
 }
 @Test void asynchronousSlicesContinueSameAuthorizationWithoutReplayingPlayerWords()throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",1);
  org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",12);
  try{operations.submit(talk("auto-slices"));var done=awaitAsync("auto-slices");assertEquals("COMPLETED",done.get("status"));assertEquals(2L,done.get("storySeconds"));var saved=json.readTree(payload("auto-slices"));assertTrue(saved.path("computationContinuations").asInt()>0);int playerWords=0;for(var e:saved.path("events"))if(player.equals(e.path("actorId").asText()) && "UTTERANCE".equals(e.path("kind").asText()))playerWords++;assertEquals(1,playerWords);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",64);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",4);}
 }
 @Test void actualProviderFailureDoesNotAutomaticallyContinueOrInventSilence()throws Exception{
  schedule(b,3600);
  AtomicInteger calls=new AtomicInteger();doAnswer(call->{calls.incrementAndGet();throw new LLMApiException("test provider unavailable",false);}).when(model).chatSubForJson(anyList());
  operations.submit(talk("provider-no-loop"));var partial=awaitAsync("provider-no-loop");assertEquals("PARTIAL",partial.get("status"));assertEquals("MODEL_UNAVAILABLE",partial.get("pendingKind"));assertEquals(0,json.readTree(payload("provider-no-loop")).path("computationContinuations").asInt());assertEquals(1,calls.get());assertTrue(story(partial).contains("能借我短刀吗"));
 }
 @Test void automaticSlicesCommitAcceptedBodyOnceAndStopAtExactWaitBound()throws Exception{
  acceptedWork(5,List.of(Map.of("kind","FACILITY","objectName","石柱","before","","after","原工作已完成")));schedule(a,3600);schedule(b,3600);waitFor(11);
  org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",2);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",12);
  try{operations.submit(nextInput("auto-body-bound","等待11秒"));var done=awaitAsync("auto-body-bound");assertEquals("COMPLETED",done.get("status"));assertEquals(11L,done.get("storySeconds"));var saved=json.readTree(payload("auto-body-bound"));assertTrue(saved.path("computationContinuations").asInt()>0);int effects=0;for(var e:saved.path("events"))if("甲的原工作实际完成。".equals(e.path("text").asText())){effects++;assertEquals(5L,e.path("endSeconds").asLong());}assertEquals(1,effects);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",64);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",4);}
 }
 @Test void continuationBoundStopsComputationWithoutExtendingWorldAuthorization()throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",1);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",1);
  try{operations.submit(talk("bounded-slices"));var partial=awaitAsync("bounded-slices");assertEquals("PARTIAL",partial.get("status"));assertEquals("COMPUTATION_BOUNDARY",partial.get("pendingKind"));assertEquals(1,json.readTree(payload("bounded-slices")).path("computationContinuations").asInt());assertTrue(((Number)partial.get("storySeconds")).longValue()<=2L);}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",64);org.springframework.test.util.ReflectionTestUtils.setField(operations,"maxContinuations",4);}
 }
 @Test void cancelledAutomaticallyContinuedSliceCannotCommitLateChoice()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",1);
  doAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return response(choice("迟到选择","迟到话语",player,""));}).when(model).chatSubForJson(anyList());
  try{operations.submit(talk("cancel-slice"));assertTrue(entered.await(10,TimeUnit.SECONDS));assertTrue(json.readTree(payload("cancel-slice")).path("computationContinuations").asInt()>0);operations.cancel(sid,pid,"cancel-slice",0);release.countDown();assertEquals("CANCELLED",awaitAsync("cancel-slice").get("status"));assertFalse(payload("cancel-slice").contains("迟到话语"));}
  finally{release.countDown();org.springframework.test.util.ReflectionTestUtils.setField(operations,"sliceStages",64);}
 }

 @Test void conditionalWaitStopsAtDeliveredCompletionAndCarriesSlowerWorkToNextOperation()throws Exception{
  String input="等甲完成检查，再解开绳结";AtomicInteger chosen=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));
   if(self.path("id").asText().equals(a) && chosen.getAndIncrement()==0)return response(choice("检查自己的工作","","","检查绳结"));
   if(self.path("id").asText().equals(b))return response(choice("整理自己的东西","","","整理衣角"));return response(choice("","","",""));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析")){
    if(m.get(1).get("content").contains(input))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等甲完成检查","seconds",20,"condition",Map.of("type","ACTION_COMPLETED","text","甲完成检查","actorId",a,"attemptContains","检查")),Map.of("type","ACTION","text","再解开绳结","whenWaitSatisfied",true,"conditionText","甲完成检查"))));
    return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待五秒","seconds",5))));
   }
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));String actor=facts.path("actorId").asText();
    if(actor.equals(a)){var visible=new LinkedHashMap<>(result("甲实际完成了绳结检查。",3,List.of()));visible.put("observations",List.of(Map.of("channel","VISUAL","text","甲实际完成了绳结检查。","action","检查绳结","status","COMPLETED")));return response(visible);}
    if(actor.equals(b))return response(result("乙整理好了衣角。",8,List.of(Map.of("kind","BODY","actorId",b,"before",Map.of("activity",""),"after",Map.of("activity","已整理")))));
    return response(result("你解开了现有绳结。",2,List.of(Map.of("kind","FACILITY","objectName","绳结","before","","after","已解开"))));
   }
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var first=operations.create(new ConversationOperationService.Request("conditional",sid,pid,scene,input,null,0L,0L));record("条件满足后立即行动",first);
  assertEquals("COMPLETED",first.get("status"));assertEquals(5L,first.get("storySeconds"));assertTrue(first.get("waitResults").toString().contains("SATISFIED"));assertFalse(story(first).contains("乙整理好了"));
  assertEquals("",physical.view(sid,actors.findById(b).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));
  var second=operations.create(new ConversationOperationService.Request("continue_time",sid,pid,scene,"等待五秒",null,0L,((Number)first.get("revision")).longValue()));record("下一操作接续在途工作",second);
  assertEquals("COMPLETED",second.get("status"));assertEquals(10L,second.get("storySeconds"));assertTrue(story(second).contains("乙整理好了"));
  assertEquals("已整理",physical.view(sid,actors.findById(b).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));
  checkpoints.restore(sid,1);assertEquals(5L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid));
 }
 @Test void hiddenCompletionDoesNotSatisfyPlayerWaitOrExecuteConditionalAction()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("私事","","","检查自己的记号"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等甲完成检查","seconds",10,"condition",Map.of("type","ACTION_COMPLETED","text","甲完成检查","actorId",a,"attemptContains","检查")),Map.of("type","ACTION","text","再解开绳结","whenWaitSatisfied",true,"conditionText","甲完成检查"))));
   if(system.contains("你结算")){var actual=new LinkedHashMap<>(result("甲私下完成了记号检查。",3,List.of()));actual.put("visibility","PRIVATE");return response(actual);}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("hidden_wait",sid,pid,scene,"等甲完成检查，再解开绳结",null,0L,0L));record("隐藏结果不能触发玩家条件",r);
  assertEquals("COMPLETED",r.get("status"));assertEquals(10L,r.get("storySeconds"));assertTrue(r.get("waitResults").toString().contains("BOUND_REACHED"));assertFalse(story(r).contains("记号"));assertFalse(payload("hidden_wait").contains(player+"|authorized_1"));
 }
 @Test void semanticCancellationIsDeliveredWithoutAnotherConditionJudgeOrFollowUp()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));return response(self.path("id").asText().equals(a)?choice("取消本次工作","","","收起检查材料"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等检查完成","condition",Map.of("type","SEMANTIC_EVENT","text","检查完成","actorId",a)),Map.of("type","ACTION","text","再解开绳结","whenWaitSatisfied",true,"conditionText","检查完成"))));
   if(system.contains("你结算")){assertTrue(m.get(1).get("content").contains("waitSubscriptions"));var n=new LinkedHashMap<>(result("甲收起已有检查材料，明确终止了这项检查。",2,List.of()));n.put("waitMatches",List.of(Map.of("conditionId","wait_0","outcome","KNOWN_INVALIDATED","basis","ACTUAL_RESULT")));return response(n);}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("cancel_condition",sid,pid,scene,"等检查完成，再解开绳结",null,0L,0L));assertEquals("COMPLETED",r.get("status"));assertEquals(2L,r.get("storySeconds"));assertTrue(r.get("waitResults").toString().contains("KNOWN_INVALIDATED"));assertFalse(payload("cancel_condition").contains(player+"|authorized_1"));
 }
 void continuingNpcMovement(boolean legacy,boolean blocked)throws Exception{
  String midway="石柱与门边之间",destination="石门边";String original=physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position").toString();var firstSegment=new LinkedHashMap<String,Object>(result("甲走到中途，仍未到门边。",3,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("position",original),"after",Map.of("position",midway)))));firstSegment.put("status","ONGOING");firstSegment.put("remainingSeconds",7);
  var choices=new AtomicInteger();var continued=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(!a.equals(json.readTree(m.get(1).get("content")).path("id").asText()))return response(choice("","","",""));choices.incrementAndGet();var d=choice("去门边等候","","","走到石门边，留在石柱所在场景");d.put("localMovement",true);d.put("next_think_seconds",3600);return response(d);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(firstSegment);return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var first=operations.create(talk("npc-move-start"));assertEquals("COMPLETED",first.get("status"));assertEquals(midway,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
  if(legacy)jdbc.update("UPDATE role_knowledge SET content=JSON_REMOVE(content,'$.decision') WHERE session_id=? AND god_id=? AND source_ref='v2:work' AND source_type='ROLE_TASK'",sid,a);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待十秒","seconds",10))));
   var facts=json.readTree(m.get(1).get("content"));assertEquals(a,facts.path("actorId").asText());int index=continued.getAndIncrement();
   if(blocked){var r=new LinkedHashMap<String,Object>(result("甲被门前落石挡住，未能继续走近。",2,List.of()));r.put("status","BLOCKED");return response(r);}
   if(index==0)return response(result("甲已经走到石门边。",2,List.of()));
   assertEquals("local movement result must record position",facts.path("rejectedResultReason").asText());return response(result("甲已经走到石门边。",2,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("position",midway),"after",Map.of("position",destination)))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("npc-move-continue","等待十秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(blocked?1:2,continued.get());assertEquals(1,choices.get(),"accepted movement is continued without asking the role to choose it again");
  assertFalse(story(done).contains("甲走到中途"),"the already committed first segment must not be replayed");
  assertEquals(blocked?midway:destination,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("position"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_TASK'",Integer.class,sid,a));
 }
 @Test void npcContinuationCannotFinishMovementOnlyInProse()throws Exception{continuingNpcMovement(false,false);}
 @Test void legacyNpcContinuationStillRequiresActualPosition()throws Exception{continuingNpcMovement(true,false);}
 @Test void blockedNpcContinuationKeepsItsActualMidwayPosition()throws Exception{continuingNpcMovement(false,true);}
 @Test @org.springframework.transaction.annotation.Transactional void pausedWorkContextReadsLargeRecordsWithoutSortingJson()throws Exception{
  long original=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
  try{jdbc.execute("SET SESSION sort_buffer_size=32768");for(int i=0;i<30;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'暂停工作',?,'ROLE_PAUSED','',?,'',?)",sid,a,"paused-large-"+i,json.writeValueAsString(Map.of("attempt","暂停动作"+i,"uncommittedExecution",Map.of("padding","x".repeat(50000)))),scene,i);
   var method=ConversationOperationService.class.getDeclaredMethod("context",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,String.class);method.setAccessible(true);var p=json.createObjectNode().put("playerRole",player).put("sceneId",scene).put("endSeconds",0);
   var context=(List<Map<String,String>>)method.invoke(operations,sid,p,a,a+"|test");var facts=json.readTree(context.get(1).get("content"));assertEquals(List.of("暂停动作29","暂停动作28","暂停动作27","暂停动作26"),json.convertValue(facts.path("pausedWork"),List.class));assertFalse(facts.path("pausedWork").toString().contains("padding"));
   assertEquals(30,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PAUSED'",Integer.class,sid,a));
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+original);}
 }
 void largeDurableQueue(String type,boolean load)throws Exception{
  long original=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
  try{
   jdbc.execute("SET SESSION sort_buffer_size=32768");
   for(int i=0;i<30;i++){
    var record=json.createObjectNode().put("index",i).put("eventId","event-"+i).put("recordType","UTTERANCE").put("actorId",player).put("padding","x".repeat(50000));var d=json.valueToTree(choice("","","","排队动作"+i));
    record.set("decision",d);record.set("decisions",d);record.set("execution",json.valueToTree(result("已接受结果"+i,5,List.of())));record.set("timing",json.createObjectNode().put("start",0).put("executionStart",0).put("sceneId",scene));record.set("jobTimes",record.path("timing"));record.put("spoken",true);
    jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'大队列',?,?,'',?,'',?)",sid,a,"large-queue-"+i,record.toString(),type,scene,i);
   }
   var p=json.createObjectNode().put("playerRole",player).put("sceneId",scene).put("endSeconds",0);p.putArray("queue");p.putObject("carried");p.putObject("decisions");p.putObject("jobTimes");p.putObject("executions");
   if(load){
    String methodName=switch(type){case "ROLE_PENDING"->"loadPendingExecutions";case "ROLE_PROCESS"->"loadProcesses";default->"loadPlans";};var method=ConversationOperationService.class.getDeclaredMethod(methodName,String.class,com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);method.invoke(operations,sid,p);
    int count=type.equals("ROLE_PLAN")?1:30;assertEquals(count,p.path("queue").size());for(int i=0;i<count;i++)assertEquals("排队动作"+i,p.path("decisions").path(p.path("queue").get(i).asText()).path("action_text").asText());
    assertEquals(type.equals("ROLE_PENDING")?0:type.equals("ROLE_PLAN")?29:30,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type=?",Integer.class,sid,a,type));
   }else{
    var method=ConversationOperationService.class.getDeclaredMethod("context",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,String.class,String.class);method.setAccessible(true);var messages=(List<Map<String,String>>)method.invoke(operations,sid,p,a,a+"|test");var facts=json.readTree(messages.get(1).get("content"));
    if(type.equals("ROLE_INBOX")){assertEquals(30,facts.path("pendingStimuli").size());for(int i=0;i<30;i++){assertEquals("large-queue-"+i,facts.path("pendingStimuli").get(i).path("source_ref").asText());assertEquals(i,json.readTree(facts.path("pendingStimuli").get(i).path("content").asText()).path("index").asInt());}}
    else if(type.equals("ROLE_PLAN")){assertEquals(30,facts.path("plannedWork").size());for(int i=0;i<30;i++)assertEquals("排队动作"+i,facts.path("plannedWork").get(i).asText());}
    else {assertEquals(4,facts.path("establishedSelf").size());for(int i=0;i<4;i++)assertEquals(i,json.readTree(facts.path("establishedSelf").get(i).asText()).path("index").asInt());}
    assertEquals(30,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type=?",Integer.class,sid,a,type));
   }
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+original);}
 }
 @Test @org.springframework.transaction.annotation.Transactional void largeInboxContextKeepsEveryMessageInArrivalOrder()throws Exception{largeDurableQueue("ROLE_INBOX",false);}
 @Test @org.springframework.transaction.annotation.Transactional void largePlannedWorkContextKeepsChoiceOrder()throws Exception{largeDurableQueue("ROLE_PLAN",false);}
 @Test @org.springframework.transaction.annotation.Transactional void largeSelfProfilesKeepTheFirstEstablishedRecords()throws Exception{largeDurableQueue("SELF_PROFILE",false);}
 @Test @org.springframework.transaction.annotation.Transactional void largeCancelledWorkReloadsWithoutDroppingChoices()throws Exception{largeDurableQueue("ROLE_PENDING",true);}
 @Test @org.springframework.transaction.annotation.Transactional void largeProcessesReloadAcceptedResultsInOrder()throws Exception{largeDurableQueue("ROLE_PROCESS",true);}
 @Test @org.springframework.transaction.annotation.Transactional void largePlansStartTheFirstReadyChoice()throws Exception{largeDurableQueue("ROLE_PLAN",true);}
 void seedDeadNpcWork(boolean speechOnly)throws Exception{
  var d=choice("原有打算",speechOnly?"这句话尚未说出。":"",player,speechOnly?"":"整理衣物");d.put("next_think_seconds",3600);
  var timing=Map.of("start",0,"executionStart",0,"sceneId",scene);
  var process=Map.of("decision",d,"execution",result("甲不应继续整理衣物。",1,List.of()),"timing",timing,"spoken",!speechOnly,"executed",false,"background",true);
  var pending=Map.of("decisions",d,"jobTimes",timing,"spokenJobs",!speechOnly,"executedJobs",false);
  var task=Map.of("attempt","整理衣物","actual",Map.of("status","ONGOING","remainingSeconds",2),"lastEndSeconds",0,"decision",d);
  for(String type:speechOnly?List.of("ROLE_PENDING"):List.of("ROLE_PROCESS","ROLE_PENDING","ROLE_TASK","ROLE_PLAN")){
   Object record=switch(type){case "ROLE_PROCESS"->process;case "ROLE_PENDING"->pending;case "ROLE_TASK"->task;default->Map.of("decision",d,"spoken",false);};String ref=type.equals("ROLE_TASK")?"v2:work":"dead-test:"+type;
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'原已接受工作',?,?,'',?,'',0)",sid,a,ref,json.writeValueAsString(record),type,scene);
  }
  schedule(a,3600);schedule(b,3600);
 }
 void markNpcDead(){jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命','{\"health\":0}','VITAL_STATE','','','',0)",sid,a);}
 AtomicInteger ordinaryWaitForDeadWork()throws Exception{
  var worlds=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待三秒","seconds",3))));worlds.incrementAndGet();return response(result("死亡角色不应仍有动作结果。",1,List.of()));}).when(model).chatForJson(anyList());return worlds;
 }
 @Test void aDeadNpcsSavedWorkCannotBlockTheLivingActorsOrTheNextRound()throws Exception{
  seedDeadNpcWork(false);markNpcDead();var worlds=ordinaryWaitForDeadWork();
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','受伤但存活','{\"health\":75}','VITAL_STATE','','','',0)",sid,b);
  var healthy=Map.of("decision",choice("自己的工作","","","整理衣领"),"execution",result("乙整理好了衣领。",1,List.of(Map.of("kind","BODY","actorId",b,"before",Map.of("activity",""),"after",Map.of("activity","衣领已整理")))),"timing",Map.of("start",0,"executionStart",0,"sceneId",scene),"spoken",true,"executed",false,"background",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'healthy-work','健康人物工作',?,'ROLE_PROCESS','',?,'',0)",sid,b,json.writeValueAsString(healthy),scene);
  var done=operations.create(nextInput("dead-work-wait","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(3L,done.get("storySeconds"));assertEquals(0,worlds.get());assertTrue(story(done).contains("乙整理好了衣领"));assertFalse(story(done).contains("甲不应继续"));assertEquals("衣领已整理",physical.view(sid,actors.findById(b).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_PROCESS','ROLE_PENDING','ROLE_TASK','ROLE_PLAN')",Integer.class,sid,a));
  assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_STOPPED'",Integer.class,sid,a));
  var again=operations.create(nextInput("dead-work-next","等待三秒"));assertEquals("COMPLETED",again.get("status"));assertEquals(6L,again.get("storySeconds"));assertEquals(0,worlds.get());verify(model,never()).chatSubForJson(anyList());
 }
 @Test void aDeadNpcsUnspokenChoiceDoesNotLockEverySubsequentInput()throws Exception{
  seedDeadNpcWork(true);markNpcDead();var worlds=ordinaryWaitForDeadWork();var done=operations.create(nextInput("dead-pending-speech","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(0,worlds.get());assertFalse(story(done).contains("这句话尚未说出"));assertEquals(3L,done.get("storySeconds"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PENDING'",Integer.class,sid,a));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void rewindingBeforeNpcDeathRestoresItsAcceptedWork()throws Exception{
  seedDeadNpcWork(false);checkpoints.refreshCurrent(sid,pid);markNpcDead();ordinaryWaitForDeadWork();var done=operations.create(nextInput("dead-work-rewind","等待三秒"));assertEquals("COMPLETED",done.get("status"));
  checkpoints.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_STOPPED'",Integer.class,sid,a));assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_PROCESS','ROLE_PENDING','ROLE_TASK','ROLE_PLAN')",Integer.class,sid,a));assertEquals(100,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("health"));
 }
 @Test void anUnconsciousActorsStaleResultCanBeRevalidatedOnceWithoutRerollingItsChoice()throws Exception{
  acceptedWork(1,List.of());schedule(a,3600);schedule(b,3600);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','昏迷','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,a,scene);
  var repairs=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待三秒","seconds",3))));
   repairs.incrementAndGet();var facts=json.readTree(m.get(1).get("content"));assertEquals("actor cannot act while unconscious",facts.path("rejectedResultReason").asText());var blocked=new LinkedHashMap<String,Object>(result("甲因失去意识，未能继续整理。",1,List.of()));blocked.put("status","BLOCKED");return response(blocked);
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("unconscious-carried","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,repairs.get());assertFalse(story(done).contains("甲的原工作实际完成"));assertTrue(story(done).contains("未能继续整理"));assertEquals(false,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("awake"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void playerDeathStillEndsTheGameWithoutStoppingLivingNpcWork()throws Exception{
  seedDeadNpcWork(false);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','玩家死亡','{\"health\":0}','VITAL_STATE','','','',0)",sid,player);
  var done=operations.create(nextInput("dead-player-boundary","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_STOPPED'",Integer.class,sid));assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_PROCESS','ROLE_PENDING','ROLE_TASK','ROLE_PLAN')",Integer.class,sid,a)>0);verifyNoInteractions(model);
 }
 @Test void dyingDuringTheCurrentRoundStopsAnAcceptedFutureAction()throws Exception{
  acceptedWork(8,List.of());schedule(a,3600);schedule(b,3600);jdbc.update("UPDATE god_profile SET base_dou=80 WHERE god_id=?",player);jdbc.update("UPDATE god_profile SET base_dou=10 WHERE god_id=?",a);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命','{\"health\":25}','VITAL_STATE','','','',0)",sid,a);
  var executions=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","与工匠甲交手，","contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU")),Map.of("type","WAIT","text","然后等待三秒","seconds",3))));
   executions.incrementAndGet();var facts=json.readTree(m.get(1).get("content"));assertEquals(player,facts.path("actorId").asText());var c=facts.path("serverContest");assertEquals("ATTACKER_WIN",c.path("outcome").asText());return response(result("你与甲实际交手，甲生命耗尽。",1,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",c.path("outcome").asText(),"fingerprint",c.path("fingerprint").asText()))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("death-during-work","与工匠甲交手，然后等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(4L,done.get("storySeconds"));assertEquals(1,executions.get());assertEquals(0,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("health"));assertFalse(story(done).contains("甲的原工作实际完成"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_STOPPED'",Integer.class,sid,a));
 }
 void sharedWorkProcesses()throws Exception{
  for(String role:List.of(a,b)){
   var d=choice("原有工作","","","整理自己的衣领");String finished=role.equals(a)?"甲衣领已整理":"乙衣领已整理";
   var record=Map.of("decision",d,"execution",result(finished,role.equals(a)?1:8,List.of(Map.of("kind","BODY","actorId",role,"before",Map.of("activity",""),"after",Map.of("activity",finished)))),"timing",Map.of("start",0,"executionStart",0,"sceneId",scene),"spoken",true,"executed",false,"background",true);
   jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'legacy:shared-work','同名引用',?,'ROLE_PROCESS','',?,'',0)",sid,role,json.writeValueAsString(record),scene);
   schedule(role,3600);
  }
 }
 com.fasterxml.jackson.databind.node.ObjectNode sharedWorkQueue(){
  var p=json.createObjectNode().put("playerRole",player).put("operationId","same-ref-cancel").put("sceneId",scene).put("endSeconds",0);String job=a+"|carry_0";p.putArray("queue").add(job);p.putArray("processed");p.putObject("carried").put(job,"legacy:shared-work");p.putObject("decisions").set(job,json.valueToTree(choice("原有工作","","","整理衣领")));p.putObject("jobTimes").set(job,json.createObjectNode().put("start",0).put("executionStart",0).put("sceneId",scene));p.putObject("executions");return p;
 }
 @Test void completingOneSharedReferenceDoesNotLoseTheOtherActorsWork()throws Exception{
  sharedWorkProcesses();var freshExecutions=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析")){String input=json.readTree(m.get(1).get("content")).path("input").asText();return response(Map.of("steps",List.of(Map.of("type","WAIT","text",input,"seconds",input.contains("十")?10:3))));}freshExecutions.incrementAndGet();return response(result("不应重新计算已接受的结果。",1,List.of()));}).when(model).chatForJson(anyList());
  var first=operations.create(nextInput("shared-ref-first","等待三秒"));assertEquals("COMPLETED",first.get("status"));assertTrue(story(first).contains("甲衣领已整理"));assertFalse(story(first).contains("乙衣领已整理"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='legacy:shared-work' AND source_type='ROLE_PROCESS'",Integer.class,sid,b));
  var second=operations.create(nextInput("shared-ref-second","等待十秒"));assertEquals("COMPLETED",second.get("status"));assertTrue(story(second).contains("乙衣领已整理"));assertFalse(story(second).contains("甲衣领已整理"));assertEquals(13L,second.get("storySeconds"));assertEquals(0,freshExecutions.get());assertEquals("乙衣领已整理",physical.view(sid,actors.findById(b).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test @org.springframework.transaction.annotation.Transactional void cancellingOneSharedReferenceKeepsTheOtherActorsProcess()throws Exception{
  sharedWorkProcesses();var p=sharedWorkQueue();var method=ConversationOperationService.class.getDeclaredMethod("preserveAcceptedWork",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);method.invoke(operations,sid,p);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='legacy:shared-work' AND source_type='ROLE_PROCESS'",Integer.class,sid,b));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",Integer.class,sid,a));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PENDING'",Integer.class,sid,a));
 }
 @Test @org.springframework.transaction.annotation.Transactional void anotherActorsImmediateSpeechDoesNotBlockASameNamedPlan()throws Exception{
  for(String role:List.of(a,b))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'legacy:shared-plan','同名计划',?,'ROLE_PLAN','',?,'',0)",sid,role,json.writeValueAsString(Map.of("decision",choice("自己原有的计划","","","安排"+role))),scene);
  var p=sharedWorkQueue();String immediate=a+"|carry_0";p.putObject("planRefs").put(immediate,"legacy:shared-plan");((com.fasterxml.jackson.databind.node.ObjectNode)p.path("decisions").path(immediate)).put("action_text","").put("speech","这句尚未说完。");
  var method=ConversationOperationService.class.getDeclaredMethod("loadPlans",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);method.invoke(operations,sid,p);
  assertEquals(2,p.path("queue").size());assertTrue(p.path("queue").get(1).asText().startsWith(b+"|"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PLAN'",Integer.class,sid,a));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PLAN'",Integer.class,sid,b));
 }
 void sleepingSpeech(boolean carried,boolean action)throws Exception{
  var d=choice("原有打算","我还没说完。",player,action?"整理衣物":"");var timing=Map.of("start",0,"executionStart",2,"sceneId",scene);
  Object record=carried?Map.of("decision",d,"execution",result("昏迷后不能继续整理。",1,List.of()),"timing",timing,"spoken",false,"executed",false,"background",true):Map.of("decisions",d,"jobTimes",timing,"spokenJobs",false,"executedJobs",false);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'sleep:accepted-speech','原有发言',?,?,'',?,'',0)",sid,a,json.writeValueAsString(record),carried?"ROLE_PROCESS":"ROLE_PENDING",scene);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','昏迷','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,a,scene);schedule(a,3600);schedule(b,3600);
 }
 @Test void anUnconsciousNpcsUnfinishedSpeechCannotBlockWaiting()throws Exception{
  sleepingSpeech(false,false);ordinaryWaitForDeadWork();var done=operations.create(nextInput("sleeping-pending-speech","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(3L,done.get("storySeconds"));assertFalse(story(done).contains("我还没说完"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD' AND content LIKE '%我还没说完%'",Integer.class,sid));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SPEECH_STOPPED'",Integer.class,sid,a));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void cancellingDuringReviewDoesNotRestoreInterruptedSpeechAfterWaking()throws Exception{
  sleepingSpeech(true,false);String thought="我只在心里考虑路线。";doAnswer(call->response(Map.of("steps",List.of(Map.of("type","PRIVATE_INTENT","text",thought))))).when(model).chatForJson(anyList());var review=operations.create(nextInput("sleeping-speech-review",thought));assertEquals("INPUT_REVIEW_REQUIRED",review.get("pendingKind"));
  operations.cancel(sid,pid,"sleeping-speech-review",0);jdbc.update("UPDATE role_knowledge SET content=JSON_SET(content,'$.awake',true) WHERE session_id=? AND god_id=? AND source_ref='body:v2'",sid,a);ordinaryWaitForDeadWork();var done=operations.create(nextInput("woken-after-cancel","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertFalse(story(done).contains("我还没说完"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD' AND content LIKE '%我还没说完%'",Integer.class,sid));
 }
 @Test void interruptingSpeechDoesNotPretendItsPendingBodyActionSucceeded()throws Exception{
  sleepingSpeech(true,true);var repairs=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待三秒","seconds",3))));repairs.incrementAndGet();var blocked=new LinkedHashMap<String,Object>(result("甲仍然昏迷，整理未能进行。",1,List.of()));blocked.put("status","BLOCKED");return response(blocked);}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("sleeping-speech-action","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,repairs.get());assertFalse(story(done).contains("我还没说完"));assertTrue(story(done).contains("整理未能进行"));assertEquals(false,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("awake"));verify(model,never()).chatSubForJson(anyList());
 }
 @Test void completeSpeechProofIsNotDiscardedMerelyBecauseTheSpeakerIsNowUnconscious()throws Exception{
  sleepingSpeech(false,false);var p=sharedWorkQueue();String job=a+"|carry_0",speech="之前确实说完的话。";((com.fasterxml.jackson.databind.node.ObjectNode)p.path("decisions").path(job)).put("speech",speech).put("action_text","");p.put("endSeconds",10);p.putObject("speechExposure").putObject(job).put("checkedThrough",10).putArray("recipients").add(a).add(player);
  var method=ConversationOperationService.class.getDeclaredMethod("interruptUnconsciousSpeech",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);assertEquals(false,method.invoke(operations,sid,p));assertEquals(speech,p.path("decisions").path(job).path("speech").asText());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SPEECH_STOPPED'",Integer.class,sid));
 }
 @Test void deferredSpeechDoesNotPreventAnAcceptedAwakeningAction()throws Exception{
  sleepingSpeech(true,true);var awakening=result("甲恢复了意识。",1,List.of(Map.of("kind","BODY","actorId",a,"before",Map.of("awake",false),"after",Map.of("awake",true))));
  jdbc.update("UPDATE role_knowledge SET content=JSON_SET(content,'$.decision.speech_timing','AFTER_ACTION','$.decision.action_text','尝试恢复意识','$.timing.executionStart',0,'$.execution',CAST(? AS JSON)) WHERE session_id=? AND god_id=? AND source_ref='sleep:accepted-speech'",json.writeValueAsString(awakening),sid,a);
  var worlds=ordinaryWaitForDeadWork();doAnswer(call->response(choice("意识已恢复","我醒了。",player,""))).when(model).chatSubForJson(anyList());var done=operations.create(nextInput("wake-before-deferred-speech","等待三秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(0,worlds.get());assertEquals(true,physical.view(sid,actors.findById(a).orElseThrow(),scenes.findById(scene).orElseThrow()).get("awake"));assertTrue(story(done).contains("我醒了"));assertFalse(story(done).contains("我还没说完"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SPEECH_STOPPED'",Integer.class,sid));
 }
 @Test void interruptedImmediateSpeechIsRemovedOnlyFromItsOwnersSavedPlan()throws Exception{
  sleepingSpeech(false,false);for(String role:List.of(a,b))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'same:plan','已选计划',?,'ROLE_PLAN','',?,'',0)",sid,role,json.writeValueAsString(Map.of("decision",choice("原选择","我还没说完。",player,"整理衣领"))),scene);
  var p=sharedWorkQueue();String job=a+"|carry_0";((com.fasterxml.jackson.databind.node.ObjectNode)p.path("decisions").path(job)).put("speech","我还没说完。").put("action_text","");p.putObject("planRefs").put(job,"same:plan");var method=ConversationOperationService.class.getDeclaredMethod("interruptUnconsciousSpeech",String.class,com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);assertEquals(true,method.invoke(operations,sid,p));
  var own=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='same:plan'",String.class,sid,a));var other=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='same:plan'",String.class,sid,b));assertEquals("",own.path("decision").path("speech").asText());assertEquals("整理衣领",own.path("decision").path("action_text").asText());assertEquals("我还没说完。",other.path("decision").path("speech").asText());
 }
 @Test void acceptedOngoingWorkContinuesWithoutRerollingRoleAttitude()throws Exception{
  AtomicInteger roleChoices=new AtomicInteger();AtomicInteger segments=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(self.path("id").asText().equals(a)){roleChoices.incrementAndGet();return response(choice("继续完成自己的工作","","","检查现有绳结"));}return response(choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待十秒","seconds",10))));
   if(system.contains("你结算")){
    if(segments.getAndIncrement()==0){var n=new LinkedHashMap<>(result("甲已检查绳结外层，内层检查仍需继续。",3,List.of()));n.put("status","ONGOING");n.put("remainingSeconds",7);return response(n);}
    assertTrue(m.get(1).get("content").contains("acceptedWork"));return response(result("甲完成了余下的实际检查。",7,List.of()));
   }return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var first=operations.create(talk("start_work"));assertEquals("COMPLETED",first.get("status"));assertEquals(5L,first.get("storySeconds"));
  var second=operations.create(new ConversationOperationService.Request("continue_work",sid,pid,scene,"等待十秒",null,0L,((Number)first.get("revision")).longValue()));record("持续工作沿已接受进度继续",second);
  assertEquals("COMPLETED",second.get("status"));assertEquals(15L,second.get("storySeconds"));assertEquals(1,roleChoices.get(),"continuing accepted work does not re-roll a role choice");assertEquals(2,segments.get());assertTrue(story(second).contains("余下的实际检查"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_ref='v2:work'",Integer.class,sid));
 }
 @Test void opposedCombatUsesApprovedStatsAndDoesNotForcePowerfulBystanderOrMagic()throws Exception{
  jdbc.update("UPDATE god_profile SET base_dou=80 WHERE god_id=?",player);jdbc.update("UPDATE god_profile SET base_dou=10,inventory='[\"止水符\"]' WHERE god_id=?",a);jdbc.update("UPDATE god_profile SET base_dou=9999 WHERE god_id=?",b);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);rolePrompts.add(m.get(1).get("content"));assertTrue(m.get(1).get("content").contains("ATTEMPT_BEGIN"));return response(choice("依照眼前交手自主判断","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","与甲交手","contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU")))));
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));var c=facts.path("serverContest");assertEquals("ATTACKER_WIN",c.path("outcome").asText());return response(result("你与甲实际交手，在这一轮中占了优势；甲仍然清醒。",3,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",c.path("outcome").asText(),"fingerprint",c.path("fingerprint").asText()))));}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("contest",sid,pid,scene,"与甲交手",null,0L,0L));record("六维基础交手与自主旁观者",r);
  assertEquals("COMPLETED",r.get("status"));assertEquals(3L,r.get("storySeconds"));assertEquals(82,actors.findById(player).orElseThrow().getBaseDou());assertEquals(8,actors.findById(a).orElseThrow().getBaseDou());
  assertEquals(9999,actors.findById(b).orElseThrow().getBaseDou());assertEquals(75,actors.findById(a).orElseThrow().getBaseGui());assertEquals(List.of("止水符"),actors.findById(a).orElseThrow().getInventory());assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
  checkpoints.restore(sid,0);assertEquals(15,actors.findById(player).orElseThrow().getBaseDou());
 }
 @Test void actualEarlyEscapeInvalidatesPrecomputedHitAndRepairsOnlyWorldResult()throws Exception{
  String destination=scene+"_escape";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外无人。',1,0)",destination,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','现有出口')",sid,scene,destination);
  AtomicInteger defended=new AtomicInteger();AtomicInteger settle=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(self.path("id").asText().equals(a)){defended.incrementAndGet();assertTrue(m.get(1).get("content").contains("ATTEMPT_BEGIN"));return response(choice("先离开交手范围","","","沿现有出口离场"));}return response(choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","与甲交手","contest",Map.of("kind","ATTACK","targetId",a,"statKey","DOU")))));
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));if(facts.path("actorId").asText().equals(a))return response(result("甲沿现有出口离开了交手范围。",1,List.of(Map.of("kind","MOVE","actorId",a,"fromSceneId",scene,"toSceneId",destination))));
    var c=facts.path("serverContest");if(settle.getAndIncrement()==0)return response(result("双方这一轮僵持。",3,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",c.path("outcome").asText(),"fingerprint",c.path("fingerprint").asText()))));
    assertEquals("UNREACHABLE",c.path("status").asText());assertTrue(facts.has("resumeFromSeconds"));var blocked=new LinkedHashMap<>(result("甲已经实际离开，你这一轮未能继续与其交手。",2,List.of()));blocked.put("status","BLOCKED");return response(blocked);
   }return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var r=operations.create(new ConversationOperationService.Request("escape",sid,pid,scene,"与甲交手",null,0L,0L));record("反应先于交手结果生效",r);
  assertEquals("COMPLETED",r.get("status"));assertEquals(3L,r.get("storySeconds"));assertEquals(1,defended.get());assertEquals(2,settle.get());assertEquals(15,actors.findById(a).orElseThrow().getBaseDou());assertFalse(story(r).contains("双方这一轮僵持"));
 }
 @Test void roleMayChangePrivatePurposeWithoutReasonWhitelistAndWithoutChangingOthers()throws Exception{
  AtomicInteger visits=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(!self.path("id").asText().equals(a)){assertFalse(m.get(1).get("content").contains("秘密离开这里"));return response(choice("","","",""));}
   var decision=new LinkedHashMap<>(choice("自己的当前考虑","","",""));if(visits.getAndIncrement()==0)decision.put("goal_update",Map.of("mode","REPLACE","purpose","秘密离开这里，按自己的打算寻找旧日伙伴"));
   else{assertTrue(m.get(1).get("content").contains("秘密离开这里"));decision.put("goal_update",Map.of("mode","ABANDON"));}return response(decision);
  }).when(model).chatSubForJson(anyList());
  var first=operations.create(talk("purpose"));assertEquals("COMPLETED",first.get("status"));assertFalse(first.toString().contains("秘密离开这里"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=? AND status='ACTIVE'",Integer.class,sid,a));
  var second=operations.create(new ConversationOperationService.Request("abandon",sid,pid,scene,"能借我短刀吗？",new ActionRequest.DialogueInput(a,"能借我短刀吗？"),0L,((Number)first.get("revision")).longValue()));assertEquals("COMPLETED",second.get("status"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=? AND status='ACTIVE'",Integer.class,sid,a));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=? AND status='ABANDONED'",Integer.class,sid,a));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_goal WHERE session_id=? AND god_id=?",Integer.class,sid,b));
 }
 Map<String,Object> expansionResult(){
  var stats=Map.of("zhi",18,"hui",21,"dou",15,"wen",20,"ben",25,"gui",75);var body=Map.of("awake",true,"bound",false,"position","凉亭旁","activity","");
  var packet=Map.of("locationName","北面的凉亭","opening","行路者在凉亭旁休息，守亭人在门边清点自己的东西。","characters",List.of(Map.of("name","行路者","stats",stats,"inventory",List.of("水囊"),"body",body,"privateBackground","自己的口令是铜铃暗号，尚未告诉旁人"),Map.of("name","守亭人","stats",stats,"inventory",List.of(),"body",body,"privateBackground","只知道自己的守亭经历")));
  return result("你沿北面的林间小路抵达凉亭，看见行路者和守亭人。",7,List.of(Map.of("kind","EXPLORE","direction","NORTH","exitName","北面的林间小路","returnRoute",true,"scene",packet)));
 }
 void explorationModel()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var self=json.readTree(m.get(1).get("content"));if(self.path("name").asText().equals("行路者"))assertTrue(m.get(1).get("content").contains("铜铃暗号"));else assertFalse(m.get(1).get("content").contains("铜铃暗号"));return response(choice("根据自己的情况决定","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(m.get(1).get("content").contains("向北探索")?Map.of("type","ACTION","text","向北探索","exploration",Map.of("direction","NORTH")):Map.of("type","ACTION","text","沿来时的路返回"))));
   if(system.contains("你结算")){var facts=json.readTree(m.get(1).get("content"));if(facts.has("worldGenerationPermission")){assertTrue(facts.path("worldGenerationPermission").path("canCreate").asBoolean());return response(expansionResult());}
    return response(result("你沿来时的路回到石柱边。",4,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",facts.path("sceneId").asText(),"toSceneId",scene))));
   }
   assertFalse(m.get(1).get("content").contains("铜铃暗号"),"narrator must never receive the private genesis packet");return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BODY","OBSERVE","EMPTY"})
 void completedExplorationCannotLeaveThePlayerInTheOldMapNode(String kind)throws Exception{
  explorationModel();AtomicInteger attempts=new AtomicInteger();String input="沿北侧山径离开这里，走到下一处开阔地点。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析")){assertEquals(scenes.findById(scene).orElseThrow().getLocationName(),facts.path("locationName").asText());assertTrue(facts.path("knownExits").isArray());return response(Map.of("steps",List.of(Map.of("type","ACTION","text",input,"exploration",Map.of("direction","NORTH")))));}
   if(system.contains("你结算")){
    if(attempts.getAndIncrement()==0){List<Map<String,Object>> fake=switch(kind){case "BODY"->List.of(Map.of("kind","BODY","actorId",player,"before",Map.of("position",facts.path("resources").path(player).path("body").path("position").asText()),"after",Map.of("position","北侧山径外的凉亭旁")));case "OBSERVE"->List.of(Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"));default->List.of();};return response(result("你离开原地，已到北面凉亭。",7,fake));}
    assertEquals("completed exploration must record scene arrival",facts.path("rejectedResultReason").asText());return response(expansionResult());
   }
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("arrival-repair",input));assertEquals("COMPLETED",done.get("status"));assertEquals(2,attempts.get());assertNotEquals(scene,((Map<?,?>)done.get("scene")).get("sceneId"));assertEquals(7L,done.get("storySeconds"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(1,json.readTree(payload("arrival-repair")).path("rejectedWorldBatches").size());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"沿来路西行上坡二十余步即返苔痕坪","true","false","","NULL"})
 void textualReturnRoutesCannotSilentlyBecomeOneWayPaths(String value)throws Exception{
  explorationModel();AtomicInteger attempts=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","向北探索","exploration",Map.of("direction","NORTH")))));
   if(system.contains("你结算")){var n=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(expansionResult());if(attempts.getAndIncrement()==0){var e=(com.fasterxml.jackson.databind.node.ObjectNode)n.path("effects").get(0);if("NULL".equals(value))e.putNull("returnRoute");else e.put("returnRoute",value);}else{assertEquals("exploration returnRoute must be boolean",facts.path("rejectedResultReason").asText());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));}return response(n);}
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("return-route-format","向北探索"));assertEquals("COMPLETED",done.get("status"));assertEquals(2,attempts.get());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
 }
 @Test void explicitExplorationCreatesAnAtomicWorldPacketAndAnActualReturnRoute()throws Exception{
  explorationModel();var first=operations.create(new ConversationOperationService.Request("explore",sid,pid,scene,"向北探索",null,0L,0L));record("主动探索并建立可运行地点",first);
  assertEquals("COMPLETED",first.get("status"));assertEquals(7L,first.get("storySeconds"));String destination=((Map<?,?>)first.get("scene")).get("sceneId").toString();assertNotEquals(scene,destination);assertEquals(2,((List<?>)((Map<?,?>)first.get("scene")).get("presentGods")).size());assertFalse(first.toString().contains("铜铃暗号"));
  assertEquals(2,worldBook.getVisitedLocations(sid).size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND content LIKE '%GENESIS%'",Integer.class,sid));
  var second=operations.create(new ConversationOperationService.Request("return",sid,pid,destination,"沿来时的路返回",null,0L,((Number)first.get("revision")).longValue()));assertEquals("COMPLETED",second.get("status"));assertEquals(scene,((Map<?,?>)second.get("scene")).get("sceneId"));assertEquals(11L,second.get("storySeconds"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
  checkpoints.restore(sid,0);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));
 }
 @Test void newlyEstablishedCharactersHaveCurrentRatherThanHistoricalActivities()throws Exception{
  explorationModel();
  var first=operations.create(new ConversationOperationService.Request("initial-activity",sid,pid,scene,"向北探索",null,0L,0L));
  assertEquals("COMPLETED",first.get("status"));String destination=((Map<?,?>)first.get("scene")).get("sceneId").toString();var location=scenes.findById(destination).orElseThrow();
  for(String id:location.getPresentGods()){
   var view=physical.view(sid,actors.findById(id).orElseThrow(),location);assertEquals(false,view.get("activityHistorical"),"the generated opening establishes the character's actual current activity");
   var stored=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='body:v2'",String.class,sid,id));
   assertTrue(stored.path("activityHistorical").isBoolean());assertFalse(stored.path("activityHistorical").asBoolean());
  }
 }
 @Test void committedTravelAndRewindRestoreItemPlacementsWithoutChangingCustody()throws Exception{
  explorationModel();var holder=actors.findById(player).orElseThrow();holder.setInventory(new ArrayList<>(List.of("布条")));actors.saveAndFlush(holder);
  String ref=itemState.owned(sid,holder).get(0).ref();placements.place(sid,holder,scene,ref,"","石柱边膝上");checkpoints.refreshCurrent(sid,pid);
  var first=operations.create(new ConversationOperationService.Request("travel-item",sid,pid,scene,"向北探索",null,0L,0L));assertEquals("COMPLETED",first.get("status"));
  assertEquals("",placements.position(sid,player,ref));assertEquals(ref,itemState.owned(sid,actors.findById(player).orElseThrow()).get(0).ref());
  checkpoints.restore(sid,0);assertEquals("石柱边膝上",placements.position(sid,player,ref));assertEquals(ref,itemState.owned(sid,actors.findById(player).orElseThrow()).get(0).ref());
 }
 @Test void returningToAnExistingPlaceCanRegisterAMissingRouteWithoutRegeneratingIt()throws Exception{
  explorationModel();var first=operations.create(new ConversationOperationService.Request("outbound",sid,pid,scene,"向北探索",null,0L,0L));assertEquals("COMPLETED",first.get("status"));String destination=((Map<?,?>)first.get("scene")).get("sceneId").toString();
  jdbc.update("DELETE FROM scene_connection WHERE session_id=? AND from_scene_id=?",sid,destination);AtomicInteger executions=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");var facts=json.readTree(m.get(1).get("content"));
   if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","原路返回","exploration",Map.of("direction","SOUTH")))));
   if(system.contains("你结算")){executions.incrementAndGet();assertEquals(scene,facts.path("worldGenerationPermission").path("existingDestination").path("sceneId").asText());return response(result("你原路返回石柱旁。",4,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",destination,"toSceneId",scene))));}
   return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var done=operations.create(new ConversationOperationService.Request("return-missing-edge",sid,pid,destination,"原路返回",null,0L,((Number)first.get("revision")).longValue()));assertEquals("COMPLETED",done.get("status"));assertEquals(scene,((Map<?,?>)done.get("scene")).get("sceneId"));assertEquals(1,executions.get());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));
 }
 @Test void illegalWorldPacketRollsBackCharactersItemsLocationAndRouteTogether()throws Exception{
  explorationModel();AtomicInteger attempts=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String system=m.get(0).get("content");if(system.contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","向北探索","exploration",Map.of("direction","NORTH")))));
   if(system.contains("你结算")){var n=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(expansionResult());if(attempts.getAndIncrement()==0)((com.fasterxml.jackson.databind.node.ObjectNode)n.path("effects").get(0).path("scene").path("characters").get(1).path("stats")).put("ben",101);return response(n);}
   assertFalse(m.get(1).get("content").contains("铜铃暗号"));return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  var first=operations.create(new ConversationOperationService.Request("bad_packet",sid,pid,scene,"向北探索",null,0L,0L));assertEquals("PARTIAL",first.get("status"));assertEquals(0L,first.get("storySeconds"));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND content LIKE '%GENESIS%'",Integer.class,sid));
  assertTrue(first.get("ruleSnapshot").toString().contains("WORLD_RESULT_UNAVAILABLE"));
  var recovered=operations.recover(sid,pid,"bad_packet",0);assertEquals("COMPLETED",recovered.get("status"));assertEquals(first.get("logId"),recovered.get("logId"));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
 }
 @Test void cancellingDuringWorldGenerationLeavesNoOrphanLocationOrCharacter()throws Exception{
  explorationModel();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","向北探索","exploration",Map.of("direction","NORTH")))));
   if(m.get(0).get("content").contains("你结算")){entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));return response(expansionResult());}return response(Map.of("story","","eventRefs",List.of()));
  }).when(model).chatForJson(anyList());
  try{operations.submit(new ConversationOperationService.Request("cancel_expansion",sid,pid,scene,"向北探索",null,0L,0L));assertTrue(entered.await(10,TimeUnit.SECONDS));operations.cancel(sid,pid,"cancel_expansion",0);release.countDown();long until=System.nanoTime()+10_000_000_000L;while(!operations.workersIdle() && System.nanoTime()<until)Thread.sleep(20);assertTrue(operations.workersIdle());assertEquals("CANCELLED",operations.read(sid,pid,"cancel_expansion",0L).get("status"));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  }finally{release.countDown();}
 }

 @Test void jointInvalidWhisperDoesNotConsumeStimuliOrSetAThinkClock()throws Exception{
  doAnswer(call->{var bad=choice("自己的想法","私语","","");bad.put("speech_scope","WHISPER");return response(bad);}).when(model).chatSubForJson(anyList());
  var partial=operations.create(talk("joint-whisper"));assertEquals("PARTIAL",partial.get("status"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('ROLE_CONSUMPTION','ROLE_SCHEDULE')",Integer.class,sid,a));
  assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INBOX'",Integer.class,sid,a)>0);
 }
 @Test void jointPurePunctuationDuringWaitFinishesWithoutHearingOrFakeTime()throws Exception{
  doAnswer(call->{var silent=choice("继续自己的事","……","","");silent.put("next_think_seconds",3600);return response(silent);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待五秒","seconds",5))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("joint-punctuation","等待五秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(5L,done.get("storySeconds"));
  for(var event:json.readTree(payload("joint-punctuation")).path("events"))assertNotEquals("UTTERANCE",event.path("kind").asText());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD'",Integer.class,sid));
 }
 AtomicInteger jointPendingWorld(String id)throws Exception{
  var choices=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())){choices.incrementAndGet();return response(choice("保留自己的选择","","","整理衣物"));}return response(choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))throw new LLMApiException("HTTP 402",false);return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var partial=operations.create(talk(id));assertEquals("PARTIAL",partial.get("status"));assertEquals("MODEL_PAYMENT_REQUIRED",partial.get("pendingKind"));return choices;
 }
 @Test void jointRewindRetainsAcceptedChoiceAndOnlyExecutesItsMissingResult()throws Exception{
  var choices=jointPendingWorld("joint-rewind");checkpoints.restore(sid,1);var rt=jdbc.queryForMap("SELECT * FROM game_runtime WHERE session_id=?",sid);String recovered=rt.get("active_operation").toString();assertNotEquals("joint-rewind",recovered);
  var snapshot=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,recovered));assertEquals("joint-rewind",snapshot.path("originOperationId").asText());assertTrue(snapshot.path("decisions").size()>0);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("原选择的实际整理完成。",3,List.of()):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.recover(sid,pid,recovered,((Number)rt.get("branch_epoch")).longValue());assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get());
  assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint_archive WHERE session_id=?",Integer.class,sid)>0);
 }
 @Test void jointCancelPreservesAcceptedNpcWithoutPretendingItHasAWorldResult()throws Exception{
  var choices=jointPendingWorld("joint-cancel");operations.cancel(sid,pid,"joint-cancel",0);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待五秒","seconds",5))));if(m.get(0).get("content").contains("你结算"))return response(result("取消计算没有替人物放弃整理。",3,List.of()));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(nextInput("joint-after-cancel","等待五秒"));assertEquals("COMPLETED",done.get("status"));assertEquals(1,choices.get());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_PENDING'",Integer.class,sid));
 }
 @Test void jointHiddenActorCannotBeAddedToAuthorizationCandidates()throws Exception{
  var body=Map.of("awake",true,"bound",false,"activity","藏在石柱后","position","石柱后","hidden",true);
  // A delivered projection, not the live roster, defines names available to the player.
  var projection=scenes.findById(scene).orElseThrow();var service=(PlayerSceneProjectionService)org.springframework.test.util.ReflectionTestUtils.getField(operations,"playerProjection");service.enter(sid,projection,player,0);
  jdbc.update("DELETE FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='OBSERVED_STATE' AND source_ref=?",sid,player,"observed:"+scene+":"+b);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("玩家输入授权解析")){assertFalse(m.get(1).get("content").contains(b));return response(Map.of("steps",List.of(Map.of("type","WAIT","text","等待一秒","seconds",1))));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(nextInput("joint-hidden","等待一秒")).get("status"));
 }


 @Test void jointSchedulerUsesReferencedWaitRatherThanTheLatestWait()throws Exception{
  var p=json.createObjectNode();p.put("playerRole",player);p.put("endSeconds",9);p.put("nextStep",0);p.putArray("queue");p.putObject("decisions");
  p.putObject("authorization").putArray("steps").addObject().put("type","ACTION").put("text","拿绳子").put("whenWaitSatisfied",true).put("conditionUnitId","first");
  p.putObject("waitResult").put("status","BOUND_REACHED");p.putArray("waitResults").addObject().put("unitId","first").put("status","SATISFIED");
  var method=ConversationOperationService.class.getDeclaredMethod("enqueuePlayerStep",com.fasterxml.jackson.databind.node.ObjectNode.class);method.setAccessible(true);
  assertEquals(true,method.invoke(operations,p));assertEquals("拿绳子",p.path("decisions").elements().next().path("action_text").asText());
  p.put("nextStep",0);p.withArray("queue").removeAll();((com.fasterxml.jackson.databind.node.ObjectNode)p.path("waitResults").get(0)).put("status","BOUND_REACHED");p.withObject("waitResult").put("status","SATISFIED");
  assertEquals(false,method.invoke(operations,p));assertEquals(0,p.path("queue").size());
 }
 @Test void jointTransactionFailureRestoresWorkerMemoryAsWellAsDatabase()throws Exception{
  jointPendingWorld("joint-rollback");var original=json.readTree(payload("joint-rollback"));var working=(com.fasterxml.jackson.databind.node.ObjectNode)original.deepCopy();
  long fence=jdbc.queryForObject("SELECT fence FROM game_runtime WHERE session_id=?",Long.class,sid);int before=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid);
  var guardField=ConversationOperationService.class.getDeclaredField("workerGuard");guardField.setAccessible(true);var payloadField=ConversationOperationService.class.getDeclaredField("workingPayload");payloadField.setAccessible(true);
  @SuppressWarnings("unchecked") var guards=(ThreadLocal<Object>)guardField.get(operations);@SuppressWarnings("unchecked") var payloads=(ThreadLocal<com.fasterxml.jackson.databind.node.ObjectNode>)payloadField.get(operations);
  var guardType=Class.forName(ConversationOperationService.class.getName()+"$WorkerGuard");var constructor=guardType.getDeclaredConstructor(String.class,long.class,String.class,long.class);constructor.setAccessible(true);
  var transaction=ConversationOperationService.class.getDeclaredMethod("sessionTx",String.class,java.util.function.Function.class);transaction.setAccessible(true);
  try{guards.set(constructor.newInstance(sid,0L,"joint-rollback",fence));payloads.set(working);
   java.util.function.Function<org.springframework.transaction.TransactionStatus,Object> fail=status->{working.put("uncommittedMarker",true);working.withObject("decisions").removeAll();jdbc.update("DELETE FROM role_knowledge WHERE session_id=?",sid);throw new IllegalStateException("injected commit failure");};
   var thrown=assertThrows(java.lang.reflect.InvocationTargetException.class,()->transaction.invoke(operations,sid,fail));assertEquals("injected commit failure",thrown.getCause().getMessage());
   assertEquals(original,working);assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));assertEquals(original,json.readTree(payload("joint-rollback")));
  }finally{guards.remove();payloads.remove();}
 }
 @Test void jointSwitchFencesLateWorldResultAndResumesTheAcceptedChoice()throws Exception{
  var choices=jointPendingWorld("joint-switch");String other=sid+"_B",otherRole=player+"_B",otherScene=scene+"_B";
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?, '乙档玩家','MORTAL','平静','[]')",otherRole,other);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'乙档房间','[]','乙档房间',0,0)",otherScene,other);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",other,pid,otherRole,otherScene);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算")){entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return response(result("旧租约迟到结果",3,List.of()));}return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  try{var future=executor.submit(()->operations.recover(sid,pid,"joint-switch",0));assertTrue(entered.await(15,TimeUnit.SECONDS));checkpoints.resume(other,pid);
   assertEquals(otherRole,jdbc.queryForObject("SELECT god_id FROM player_profile WHERE player_id=?",String.class,pid));assertThrows(RuntimeException.class,()->operations.read(sid,pid,"joint-switch",0L));
   release.countDown();try{future.get(15,TimeUnit.SECONDS);}catch(ExecutionException expected){}assertFalse(payload("joint-switch").contains("旧租约迟到结果"));
   assertEquals("PARTIAL",jdbc.queryForObject("SELECT status FROM game_operation WHERE session_id=? AND operation_id='joint-switch'",String.class,sid));
   checkpoints.resume(sid,pid);assertEquals(player,jdbc.queryForObject("SELECT god_id FROM player_profile WHERE player_id=?",String.class,pid));
   doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("原选择最终完成",3,List.of()):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
   assertEquals("COMPLETED",operations.recover(sid,pid,"joint-switch",0).get("status"));assertEquals(1,choices.get());
  }finally{release.countDown();executor.shutdownNow();checkpoints.resume(sid,pid);jdbc.update("DELETE FROM game_runtime WHERE session_id=?",other);jdbc.update("DELETE FROM game_session WHERE session_id=?",other);jdbc.update("DELETE FROM game_scene WHERE session_id=?",other);jdbc.update("DELETE FROM god_profile WHERE session_id=?",other);}
 }

 @Test void perceptionPrivateExperienceStaysWithActorAndVisibleProcessStillReachesPlayer()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("我自己判断","","","摸石柱"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(Map.of("process","甲触到石面，刺痛伴随细沙触感。","durationSeconds",3,"effects",List.of(),"status","COMPLETED","observations",List.of(Map.of("channel","VISUAL","text","甲伸手触摸了石柱表面。","action","触摸石柱"))));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("perception-self"));assertEquals("COMPLETED",done.get("status"));assertTrue(story(done).contains("触摸"));assertFalse(done.toString().contains("刺痛"));assertFalse(transcript.read(sid,pid,null).toString().contains("刺痛"));
  assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SCENE_ATTEMPT' LIMIT 1",String.class,sid,a).contains("刺痛"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id<>? AND content LIKE '%刺痛%'",Integer.class,sid,a));
 }
 @Test void perceptionLegacyExperienceCannotBecomePublicDuringRecovery()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("自己的判断","","","摸石柱"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("你结算"))return response(Map.of("process","只有本人察觉的细沙触感","durationSeconds",3,"effects",List.of(),"status","COMPLETED"));return response(Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("legacy-perception"));assertEquals("COMPLETED",done.get("status"));assertFalse(done.toString().contains("细沙触感"));
  assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SCENE_ATTEMPT' LIMIT 1",String.class,sid,a).contains("细沙触感"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id<>? AND content LIKE '%细沙触感%'",Integer.class,sid,a));
 }
 @Test void perceptionPendingExecutionIsInternalAcrossListContextAndShare()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?, 'pending:private','待续所有权','隐藏候选与冻结输入','ROLE_PENDING','',?,'',0)",sid,player,scene);
  long id=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND source_ref='pending:private'",Long.class,sid);
  assertFalse(knowledge.list(sid,player).toString().contains("冻结输入"));assertFalse(knowledge.privateContext(sid,player).contains("冻结输入"));
  assertTrue(knowledge.share(sid,player,a,id,"把待续所有权告诉甲",scenes.findById(scene).orElseThrow(),Map.of(player,actors.findById(player).orElseThrow(),a,actors.findById(a).orElseThrow()),0).contains("内部角色状态"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TOLD'",Integer.class,sid,a));
 }
 void perceptionSpeechAndMove(boolean arriving,int moveAt)throws Exception{perceptionSpeechAndMove(arriving,moveAt,false);}
 void perceptionSpeechAndMove(boolean arriving,int moveAt,boolean reverse)throws Exception{
  String elsewhere=scene+"_speech";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外',?,'门外',1,0)",elsewhere,sid,arriving?jsonArray(b):"[]");
  if(arriving)jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",jsonArray(a),scene);
  String from=arriving?elsewhere:scene,to=arriving?scene:elsewhere;jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,from,to);schedule(a,3600);schedule(b,3600);
  var p=json.createObjectNode().put("operationId","perception-boundary").put("playerRole",player).put("sceneId",scene).put("location","石柱边").put("round",1).put("endSeconds",0).put("authorizedThrough",4).put("waitUntil",4).put("waitStart",0);
  for(String key:List.of("queue","events","processed","spokenJobs","executedJobs","backgroundJobs","contestBegun"))p.putArray(key);
  for(String key:List.of("decisions","executions","jobTimes","carried","contexts","executionContexts","planRefs"))p.putObject(key);
  String voice=a+"|voice",move=b+"|move";p.withArray("queue").add(reverse?move:voice).add(reverse?voice:move);
  double rate=(double)org.springframework.test.util.ReflectionTestUtils.getField(operations,"speechRate");String speech="甲".repeat((int)(4*rate));assertEquals(4,com.bs.narrative.simulation.SpeechTime.seconds(speech,rate));
  p.withObject("decisions").set(voice,json.valueToTree(choice("",speech,"","")));p.withObject("decisions").set(move,json.valueToTree(choice("","","","穿过门")));
  p.withObject("jobTimes").set(voice,json.createObjectNode().put("start",0).put("sceneId",scene));p.withObject("jobTimes").set(move,json.createObjectNode().put("start",0).put("executionStart",0).put("sceneId",from));
  p.withObject("executions").set(move,json.valueToTree(result("乙穿过门。",moveAt,List.of(Map.of("kind","MOVE","actorId",b,"fromSceneId",from,"toSceneId",to)))));
  var commit=ConversationOperationService.class.getDeclaredMethod("commitBatch",String.class,String.class,com.fasterxml.jackson.databind.node.ObjectNode.class,List.class);commit.setAccessible(true);
  var transaction=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(operations,"tx");
  transaction.executeWithoutResult(status->{try{commit.invoke(operations,sid,"perception-boundary",p,reverse?List.of(move,voice):List.of(voice,move));if(moveAt<4)commit.invoke(operations,sid,"perception-boundary",p,List.of(voice));}catch(Exception failure){throw new RuntimeException(failure);}});
  int heard=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD' AND content=?",Integer.class,sid,b,speech);
  assertEquals(!arriving && moveAt==4?1:0,heard);
  int canonical=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.recordType'))='UTTERANCE'",Integer.class,sid,b);assertEquals(heard,canonical);
 }
 @Test void perceptionArrivalAtSpeechEndDoesNotGetFullUtterance()throws Exception{perceptionSpeechAndMove(true,4);}
 @Test void perceptionArrivalMidSpeechDoesNotGetFullUtterance()throws Exception{perceptionSpeechAndMove(true,2);}
 @Test void perceptionDepartureAtSpeechEndRetainsFullUtterance()throws Exception{perceptionSpeechAndMove(false,4);}
 @Test void perceptionDepartureMidSpeechDoesNotGetFullUtterance()throws Exception{perceptionSpeechAndMove(false,2);}
 @Test void perceptionReversingQueueOrderDoesNotGrantTheNewcomerTheSpeech()throws Exception{perceptionSpeechAndMove(true,4,true);}

 @Test void exactGiftPromiseIsFulfilledThroughTheNewOperationWithDuplicateNames()throws Exception{
  jdbc.update("UPDATE god_profile SET inventory='[\"短刀\",\"短刀\"]' WHERE god_id=?",a);
  var giver=actors.findById(a).orElseThrow();String ref=itemState.owned(sid,giver).get(0).ref();
  String promise=json.writeValueAsString(Map.of("speaker",a,"recipient",player,"kind","GIVE_ITEM","value","短刀","status","OPEN","dueTick",1000));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'test:exact-promise','承诺',?,'PROMISE','',?,'',0)",sid,a,promise,scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(a.equals(json.readTree(m.get(1).get("content")).path("id").asText())?choice("我自行决定赠予其中一把","","","赠送一把短刀"):choice("","","",""));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);return response(m.get(0).get("content").contains("你结算")?result("甲交出其中一把短刀。",2,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","GIFT"))):Map.of("story","","eventRefs",List.of()));}).when(model).chatForJson(anyList());
  var done=operations.create(talk("exact-promise"));assertEquals("COMPLETED",done.get("status"));
  assertEquals("FULFILLED",json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='test:exact-promise'",String.class,sid,a)).path("status").asText());
  assertEquals(1,itemState.owned(sid,actors.findById(a).orElseThrow()).size());assertTrue(itemState.owned(sid,actors.findById(player).orElseThrow()).stream().anyMatch(i->ref.equals(i.ref())));
 }
 // Cross-review: all scheduler time advances must retain continuous hearing evidence.
 com.fasterxml.jackson.databind.node.ObjectNode crossSpeech()throws Exception{
  jdbc.update("DELETE FROM role_knowledge WHERE session_id=? AND source_ref='v2:schedule'",sid);schedule(a,3600);schedule(b,3600);
  var p=json.createObjectNode().put("operationId","cross-review").put("playerRole",player).put("sceneId",scene).put("location","石柱边").put("round",1).put("endSeconds",0).put("authorizedThrough",4);
  for(String key:List.of("queue","events","processed","spokenJobs","executedJobs","backgroundJobs","contestBegun"))p.putArray(key);
  for(String key:List.of("decisions","executions","jobTimes","carried","contexts","executionContexts","planRefs"))p.putObject(key);
  double rate=(double)org.springframework.test.util.ReflectionTestUtils.getField(operations,"speechRate");String speech="甲".repeat((int)(4*rate));assertEquals(4,com.bs.narrative.simulation.SpeechTime.seconds(speech,rate));
  String job=a+"|voice";p.withArray("queue").add(job);p.withObject("decisions").set(job,json.valueToTree(choice("",speech,"","")));p.withObject("jobTimes").set(job,json.createObjectNode().put("start",0).put("sceneId",scene));return p;
 }
 Object crossInvoke(String name,com.fasterxml.jackson.databind.node.ObjectNode p,List<String> jobs)throws Exception{
  var types=new ArrayList<Class<?>>();var args=new ArrayList<Object>();types.add(String.class);args.add(sid);
  if(name.equals("commitBatch") || name.equals("emitSafeSpeech")){types.add(String.class);args.add("cross-review");}
  types.add(com.fasterxml.jackson.databind.node.ObjectNode.class);args.add(p);if(jobs!=null){types.add(List.class);args.add(jobs);}
  var method=ConversationOperationService.class.getDeclaredMethod(name,types.toArray(Class<?>[]::new));method.setAccessible(true);
  var transaction=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(operations,"tx");
  return transaction.execute(status->{try{return method.invoke(operations,args.toArray());}catch(Exception failure){throw new RuntimeException(failure);}});
 }
 void assertCrossHeard(com.fasterxml.jackson.databind.node.ObjectNode p){
  assertEquals(4,p.path("endSeconds").asLong());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD'",Integer.class,sid,b));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.recordType'))='UTTERANCE'",Integer.class,sid,b));
 }
 @Test void crossReviewAutonomousBoundaryKeepsContinuousHearing()throws Exception{
  var p=crossSpeech();jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_ref='v2:schedule'", "{\"nextSeconds\":2}",sid,b);String voice=a+"|voice";
  assertEquals(true,crossInvoke("prepareBoundary",p,List.of(voice)));assertEquals(2,p.path("endSeconds").asLong());
  crossInvoke("commitBatch",p,List.of(voice));assertCrossHeard(p);
 }
 @Test void crossReviewAdvanceWaitWakeKeepsContinuousHearing()throws Exception{
  var p=crossSpeech();p.put("waitUntil",4).put("waitStart",0);jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_ref='v2:schedule'", "{\"nextSeconds\":2}",sid,b);
  assertEquals(true,crossInvoke("advanceWait",p,null));assertEquals(2,p.path("endSeconds").asLong());
  crossInvoke("commitBatch",p,List.of(a+"|voice"));assertCrossHeard(p);
 }
 @Test void crossReviewWaitCutoffPersistsHearingForNextOperation()throws Exception{
  var p=crossSpeech();p.put("waitUntil",2).put("waitStart",0);
  assertEquals(true,crossInvoke("prepareBoundary",p,List.of(a+"|voice")));assertEquals(2,p.path("endSeconds").asLong());
  var saved=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_PROCESS'",String.class,sid,a));assertEquals(2,saved.path("speechExposure").path("checkedThrough").asLong());
  var restored=crossSpeech();restored.withArray("queue").removeAll();restored.withObject("decisions").removeAll();restored.withObject("jobTimes").removeAll();restored.put("endSeconds",2);
  crossInvoke("loadProcesses",restored,null);String carried=restored.path("queue").get(0).asText();crossInvoke("commitBatch",restored,List.of(carried));assertCrossHeard(restored);
 }
 @Test void crossReviewAdvanceWaitLimitPreservesHearing()throws Exception{
  var p=crossSpeech();p.put("waitUntil",2).put("waitStart",0);crossInvoke("advanceWait",p,null);assertEquals(2,p.path("speechExposure").path(a+"|voice").path("checkedThrough").asLong());
  crossInvoke("commitBatch",p,List.of(a+"|voice"));assertCrossHeard(p);
 }
 @Test void crossReviewEarlySpeechCannotSkipDueAutonomousChoice()throws Exception{
  var p=crossSpeech();jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_ref='v2:schedule'", "{\"nextSeconds\":2}",sid,b);String voice=a+"|voice";crossInvoke("emitSafeSpeech",p,List.of(voice));
  assertEquals(0,p.path("endSeconds").asLong());assertTrue(p.path("spokenJobs").isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD'",Integer.class,sid));
  assertEquals(true,crossInvoke("prepareBoundary",p,List.of(voice)));assertEquals(2,p.path("endSeconds").asLong());assertTrue(p.path("queue").toString().contains("autonomous"));
 }
 @Test void crossReviewEarlySpeechStillWorksBeforeFutureAutonomousChoice()throws Exception{
  var p=crossSpeech();crossInvoke("emitSafeSpeech",p,List.of(a+"|voice"));assertCrossHeard(p);assertEquals(1,p.path("spokenJobs").size());
 }
}


