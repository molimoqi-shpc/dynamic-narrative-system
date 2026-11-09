package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
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

/** Exercises the production story path against committed MySQL state, without paid model calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class SceneStoryIT {
 @Autowired ConversationOperationService operations;
 @Autowired SessionStateService checkpoints;
 @Autowired JdbcTemplate jdbc;
 @Autowired GodProfileRepository actors;
 @Autowired GameSceneRepository scenes;
 @Autowired OpenSceneService physical;
 @Autowired ItemStateService items;
 @Autowired TemporalContextService temporal;
 @Autowired KnowledgeService knowledge;
 @Autowired CharacterPanelService panel;
 @Autowired OperationConflictService conflicts;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();String sid,pid,player,a,b,scene;
 final AtomicInteger roleCalls=new AtomicInteger(),mainCalls=new AtomicInteger();
 @BeforeEach void seed()throws Exception{
  ReflectionTestUtils.setField(operations,"sceneStory",true);
  ReflectionTestUtils.setField(operations,"storyFirst",false);
  String suffix=UUID.randomUUID().toString().substring(0,8);sid="story_it_"+suffix;pid="story_u_"+suffix;player="story_p_"+suffix;a="story_a_"+suffix;b="story_b_"+suffix;scene="story_s_"+suffix;
  for(String role:List.of(player,a,b))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',15,15,15,15,25,75,?)",role,sid,role.equals(player)?"玩家":role.equals(a)?"工匠甲":"守望者乙",role.equals(a)?"[\"短刀\"]":"[]");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'石柱边',?,'工匠甲和守望者乙在场。石柱上的绳结实际存在。',0,0)",scene,sid,json.writeValueAsString(List.of(a,b)));
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,?,'unused',?,?,'[]')",pid,player,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",sid,pid,player,scene);checkpoints.initialize(sid,pid);
  for(String role:List.of(a,b))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'private-self','背景',?,'SELF_PROFILE','',?,'',0)",sid,role,"仅"+role+"知道的私事",scene);
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);assertTrue(m.get(0).get("content").contains("意愿API"));var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();String other=role.equals(a)?b:a;assertTrue(m.get(1).get("content").contains("仅"+role+"知道的私事"));assertFalse(m.get(1).get("content").contains("仅"+other+"知道的私事"));roleCalls.incrementAndGet();return response(Map.of("thought","私有思考标记","intention","查清绳结与刻痕","actionDirection","检查并比对","communicationIntent","愿意解释已知部分","boundaries","不承诺结盟"));}).when(model).chatSubForJson(anyList());
 }
 LLMResponse response(Object value)throws Exception{String raw=json.writeValueAsString(value);return new LLMResponse(raw,raw,0,0,0,0);}
 ConversationOperationService.Request request(String id,String text){var rows=jdbc.queryForList("SELECT * FROM game_runtime WHERE session_id=?",sid);var rt=rows.isEmpty()?Map.of("branch_epoch",0L,"revision",0L):rows.get(0);String here=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);return new ConversationOperationService.Request(id,sid,pid,here,text,null,((Number)rt.get("branch_epoch")).longValue(),((Number)rt.get("revision")).longValue(),true);}
 ObjectNode action(String actor,String process,int at,int duration,List<?> effects){var n=json.createObjectNode().put("kind","ACTION").put("actorId",actor).put("process",process).put("visibility","PUBLIC").put("status","COMPLETED").put("atSeconds",at).put("durationSeconds",duration);n.set("effects",json.valueToTree(effects));n.putArray("observations").addObject().put("channel","VISUAL").put("text",process);return n;}
 ObjectNode speech(String actor,String text,int at){return json.createObjectNode().put("kind","SPEECH").put("actorId",actor).put("targetId",player).put("scope","DIRECTED").put("speech",text).put("atSeconds",at);}
 ObjectNode passage(String prose,String stop,int seconds,JsonNode... events){var n=json.createObjectNode().put("story",prose).put("stop",stop).put("elapsedSeconds",seconds);var es=n.putArray("events");for(var e:events)es.add(e);return n;}
 JsonNode payload(String id)throws Exception{return json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,id));}
 void main(java.util.function.Function<JsonNode,ObjectNode> answer)throws Exception{doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("【一生终章】"))return response(Map.of("death_summary","此生走马灯，承接实际死亡。","final_evaluation","此生终评。"));assertTrue(m.get(0).get("content").contains("唯一主创API"));var facts=json.readTree(m.get(1).get("content"));assertFalse(facts.has("serverEffectBaselines"));assertEquals(2,facts.path("roleDirections").size());mainCalls.incrementAndGet();return response(answer.apply(facts));}).when(model).chatForJson(anyList());}
 @Test void coherentInvestigationCommitsMainProseAndStateOnce()throws Exception{
  String prose="你和工匠甲一道检查石柱。绳结松开后，完整刻痕显露。甲说：“这里是一道修补记号。”比对绳结的新旧与两处刻痕，你们确认它不是通行暗号；下一步可沿旧路找当年的修柱人。";
  main(facts->passage(prose,"RESOLVED",600,action(a,"甲解开绳结并露出完整刻痕。",0,300,List.of(Map.of("kind","FACILITY","objectName","石柱","after","绳结已解开，完整修补刻痕已显露"))),speech(a,"这里是一道修补记号。",320)));
  var req=request("whole-event","把石柱和绳结调查到能确认的结论，普通询问自行衔接。");var done=operations.create(req);
  assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(600L,done.get("storySeconds"));var p=payload("whole-event");assertEquals(prose,p.path("story").asText());assertFalse(done.toString().contains("私有思考标记"));assertEquals("SCENE_V1",p.path("storyEngine").asText());assertTrue(temporal.sceneState(sid,scene).toString().contains("完整修补刻痕"));
  operations.create(req);operations.read(sid,pid,"whole-event",0L);assertEquals(2,roleCalls.get());assertEquals(1,mainCalls.get());assertEquals("narrator："+prose,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
 }
 @Test void recentPublishedChapterCarriesRecordedStartAndEndWithoutRecountingIt() throws Exception {
  main(facts->{if(mainCalls.get()>1){assertEquals(0,facts.at("/previousChapters/0/recordedStartSeconds").asLong());assertEquals(600,facts.at("/previousChapters/0/recordedEndSeconds").asLong());assertEquals(600,facts.path("storySeconds").asLong());}return passage("调查有了新的明确进展。","RESOLVED",600);});
  assertEquals("COMPLETED",operations.create(request("timeline-first","查清第一处线索")).get("status"));assertEquals("COMPLETED",operations.create(request("timeline-second","承接刚才结果继续")).get("status"));
 }
 @Test void legacyChapterWithNullClockKeepsItsProseWithoutInventingTime() throws Exception {
  main(facts->{if(mainCalls.get()>1){var prior=facts.path("previousChapters").get(0);assertEquals("narrator：旧章原文保持可查。",prior.path("story").asText());assertFalse(prior.has("recordedStartSeconds"));assertFalse(prior.has("recordedEndSeconds"));}return passage("旧章原文保持可查。","RESOLVED",120);});
  assertEquals("COMPLETED",operations.create(request("legacy-clock-first","查第一条线索")).get("status"));
  jdbc.update("UPDATE interaction_log SET rule_snapshot=JSON_SET(rule_snapshot,'$.storyStartSeconds',NULL,'$.storySeconds',NULL) WHERE session_id=?",sid);
  assertEquals("COMPLETED",operations.create(request("legacy-clock-second","继续查访")).get("status"));
 }
 @Test void invalidTailRollsBackWholePassageAndRepairKeepsIntentions()throws Exception{
  main(facts->{boolean first=mainCalls.get()==1;return passage("工匠甲完成调查，刻痕显露。","RESOLVED",600,action(a,"甲清理石柱，露出刻痕。",0,120,List.of(Map.of("kind","FACILITY","objectName","石柱","after","刻痕已显露"))),action(b,"乙整理衣领。",150,60,List.of(Map.of("kind","BODY","actorId",b,"after",first?Map.of("health",500):Map.of("activity","衣领已整理")))));});
  var done=operations.create(request("repair-whole","完成调查。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(2,roleCalls.get());assertEquals(2,mainCalls.get());var p=payload("repair-whole");assertEquals(1,p.path("sceneRejectedDrafts").size());assertEquals(2,p.path("events").size(),"rolled back in-memory events must not survive");assertEquals(1,p.path("scenePassages").size());assertEquals(100,new VitalStateService(jdbc).health(sid,b));
 }
 @Test void allianceStopsBeforePlayerConsent()throws Exception{
  String line="我提议与你结盟，期限三分钟，你愿意吗？";
  main(facts->{var p=passage("调查结束，工匠甲转身问你：“"+line+"”他等着你的决定。","DECISION",120,speech(a,line,90));p.putObject("decision").put("type","ALLIANCE").put("eventIndex",0).put("question","是否接受工匠甲的结盟提议？");return p;});
  var done=operations.create(request("alliance","一起调查，重大承诺等我表态。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertTrue(payload("alliance").path("storyStop").path("requiresInput").asBoolean());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ACTIVE'",Integer.class,sid));
 }
 @Test void continueRefreshesIntentionsAndRetainsExactMainPassages()throws Exception{
  main(facts->{boolean first=mainCalls.get()==1;if(!first)assertEquals("先查清绳结，再核对刻痕。",facts.path("alreadyHappenedThisChapter").get(0).asText());return passage(first?"先查清绳结，再核对刻痕。":"新的比对确认了修补记号，一件事情由此有了结论。",first?"CONTINUE":"RESOLVED",300,action(a,first?"甲检查了绳结。":"甲完成比对。",0,240,List.of()));});
  var done=operations.create(request("continue","查清绳结的来历。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(600L,done.get("storySeconds"));assertEquals(4,roleCalls.get());assertEquals(2,mainCalls.get());assertEquals("先查清绳结，再核对刻痕。\n\n新的比对确认了修补记号，一件事情由此有了结论。",payload("continue").path("story").asText());
 }
 @Test void publishedPassageSurvivesRolePauseAndRecoveryOnlyRequestsNewEvents()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  var pause=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{if(pause.get())throw new LLMApiException("MODEL_CALL_LIMIT: test pause after published passage",false);roleCalls.incrementAndGet();return response(Map.of("intention","按已知进展继续核对新依据"));}).when(model).chatSubForJson(anyList());
  String first="你们已看过绳结并认出修补记号，甲随后取出旧账，准备核对修柱人的姓名。",next="甲翻开旧账，找到了修柱人的姓名与工钱记录，先前的记号由此有了可追查的来历。";
  main(facts->{
   if(mainCalls.get()==1){assertEquals("NEW_PASSAGE",facts.at("/generationScope/mode").asText());pause.set(true);var d=passage(first,"CONTINUE",300);d.putArray("sceneOutcomes");return d;}
   assertEquals("CONTINUE_AFTER_COMMITTED",facts.at("/generationScope/mode").asText());assertEquals(1,facts.path("alreadyHappenedThisChapter").size());assertEquals(first,facts.path("alreadyHappenedThisChapter").get(0).asText());var d=passage(next,"RESOLVED",180);d.putArray("sceneOutcomes");return d;
  });
  var done=operations.create(request("published-recover","查清修补人的来历，普通问询自然完成。"));assertEquals("PARTIAL",done.get("status"),done.toString());assertEquals(first,payload("published-recover").path("story").asText());assertEquals(1,mainCalls.get());
  pause.set(false);done=operations.recover(sid,pid,"published-recover",0);assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(first+"\n\n"+next,payload("published-recover").path("story").asText());assertEquals(2,payload("published-recover").path("scenePassages").size());assertEquals(480L,payload("published-recover").path("endSeconds").asLong());
  operations.read(sid,pid,"published-recover",0L);assertEquals(2,mainCalls.get());assertEquals(4,roleCalls.get());
 }
 @Test void personalHistoryRetainsSelfProfileAndLabelsOldIntentionsWithoutExecutingThem()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'old-plan','旧意愿',?,'ROLE_PROFILE','',?,'',0)",sid,a,"{\"executionStatus\":\"PROPOSED\",\"direction\":{\"intention\":\"以前想去南坡\"}}",scene);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'structured-self','原身份',?,'SELF_PROFILE','',?,'',0)",sid,a,"{\"profile\":\"桥西补铜匠，常年在桥西铺营生\",\"narrativeTier\":\"EXTRA\"}",scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));if(a.equals(own.path("id").asText())){
   boolean plan=false,self=false;for(var record:own.path("ownRecords")){
    if(record.path("content").path("direction").path("intention").asText().equals("以前想去南坡")){plan=true;assertEquals("PROPOSED",record.path("content").path("executionStatus").asText());assertTrue(record.path("recordMeaning").asText().contains("不等于已经发生"));}
    if(record.path("content").path("profile").asText().equals("桥西补铜匠，常年在桥西铺营生"))self=true;
   }assertTrue(plan);assertTrue(self);assertFalse(own.has("establishedStoryFacts"));
  }roleCalls.incrementAndGet();return response(Map.of("intention","留在铺中，只讲本人知道的事"));}).when(model).chatSubForJson(anyList());
  main(facts->{var d=passage("甲仍在铺中，说明旧想法尚未成行。你们核实了他本人知道的事。","RESOLVED",120);d.putArray("sceneOutcomes");return d;});
  assertEquals("COMPLETED",operations.create(request("plan-not-history","核对甲现在的实际处境")).get("status"));assertEquals(scene,physical.actorScene(sid,a).orElseThrow().getSceneId());assertEquals(2,roleCalls.get());assertEquals(1,mainCalls.get());
 }
 @Test void transferThenPlacementUsesNewCustodyWithoutReplayingDelivery()throws Exception{
  String ref=items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  main(facts->passage("工匠甲将短刀借给你，你接过后放在膝上。","RESOLVED",180,action(a,"甲把短刀借给玩家。",0,10,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",player,"itemRef",ref,"mode","LEND"))),action(player,"你将借来的短刀放在膝上。",20,5,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"after","膝上")))));
  var req=request("transfer","向工匠甲借短刀用来调查，他同意才借。");var done=operations.create(req);assertEquals("COMPLETED",done.get("status"),done.toString());assertTrue(items.owned(sid,actors.findById(a).orElseThrow()).isEmpty());assertEquals(ref,items.owned(sid,actors.findById(player).orElseThrow()).get(0).ref());operations.create(req);assertEquals(1,mainCalls.get());
 }

 @Test void failedDraftRecoveryReusesRolesAndNeverPublishesRejectedProse()throws Exception{
  var repaired=new java.util.concurrent.atomic.AtomicBoolean(false);
  main(facts->passage(repaired.get()?"实际调查完成。":"未提交的失败正文。","RESOLVED",300,action(a,"甲完成了调查。",0,200,List.of(Map.of("kind","BODY","actorId",a,"after",repaired.get()?Map.of("activity","调查完成"):Map.of("health",900))))));
  var done=operations.create(request("recover","完成调查。"));assertEquals("PARTIAL",done.get("status"));assertFalse(payload("recover").path("story").asText().contains("未提交"));assertEquals(2,roleCalls.get());assertEquals(2,mainCalls.get());repaired.set(true);done=operations.recover(sid,pid,"recover",0);assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(2,roleCalls.get());assertEquals(3,mainCalls.get());assertEquals(1,payload("recover").path("scenePassages").size());
 }
 @Test void whisperAndPrivateActionAreKnownOnlyToTheirActualParticipants()throws Exception{
  main(facts->{var whisper=speech(a,"只有乙听到的悄悄话",10);whisper.put("targetId",b).put("scope","WHISPER");var own=action(a,"甲心里重新判断过去的线索。",30,20,List.of());own.put("visibility","PRIVATE");return passage("你等在石柱旁，两人压低声音交谈片刻，随后恢复安静。","RESOLVED",60,whisper,own);});
  var done=operations.create(request("private","稍作观察。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertFalse(done.toString().contains("只有乙听到"));assertFalse(done.toString().contains("甲心里"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND content LIKE '%只有乙听到%'",Integer.class,sid,player));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND content LIKE '%只有乙听到%'",Integer.class,sid,b));
 }
 @Test void travelDoesNotGiveOldWitnessesKnowledgeOfDestination()throws Exception{
  String to=scene+"_far";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'山径','[]','山径有一块白石。',1,0)",to,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','山径')",sid,scene,to);
  main(facts->{assertEquals(to,facts.path("routeDestinations").get(0).path("sceneId").asText());assertEquals("山径有一块白石。",facts.path("routeDestinations").get(0).path("openingEnvironment").asText());assertFalse(facts.path("privateRoleContexts").toString().contains("山径有一块白石"));return passage("你沿山径抵达白石边，回头已看不见石柱。","RESOLVED",300,action(player,"你沿山径离开石柱，抵达山径。",0,300,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",to))));});
  var done=operations.create(request("travel","沿山径去下一个地点。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(to,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='VISITED_SCENE'",Integer.class,sid,a,to));
 }

 @Test void explicitPlayerDialogueReachesRoleContextOnceAndMainAuthorsReply()throws Exception{
  String original="请解释石柱上这些刻痕。";
  main(facts->{assertEquals("TALK",facts.path("inputMode").asText());assertEquals(original,facts.path("playerUtterance").path("speech").asText());assertTrue(facts.path("characterDossiers").path(a).path("personallyKnown").path("pendingStimuli").toString().contains(original));return passage("工匠甲听完你的询问，答道：“这是修补时留下的标记。”他指明两处能互相印证的刀痕。","RESOLVED",90,speech(a,"这是修补时留下的标记。",10));});
  var r=request("talk-main",original);var direct=new ConversationOperationService.Request(r.operationId(),sid,pid,scene,original,new com.bs.narrative.dto.ActionRequest.DialogueInput(a,original),r.branchEpoch(),r.expectedRevision(),false);var done=operations.create(direct);assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals("SCENE_V1",payload("talk-main").path("storyEngine").asText());assertEquals(2,payload("talk-main").path("events").size());operations.create(direct);assertEquals(1,mainCalls.get());
 }
 @Test void absorbedLegacyIntentIsRetainedButCannotBeSharedAsKnowledge()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'old-work','原意愿','{\"decisions\":{\"action_text\":\"继续调查\"},\"secret\":\"冻结的大上下文\"}','ROLE_PENDING','',?,'',0)",sid,a,scene);
  main(facts->passage("调查已有结论。","RESOLVED",60,action(a,"甲确认刻痕。",0,50,List.of())));
  assertEquals("COMPLETED",operations.create(request("legacy","完成调查。")).get("status"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_INTEGRATED' AND content LIKE '%冻结的大上下文%'",Integer.class,sid,a));assertFalse(knowledge.privateContext(sid,a).contains("冻结的大上下文"));
 }

 @Test void mainContinuitySurvivesBeyondRecentFourChapters()throws Exception{
  main(facts->{if(mainCalls.get()>1)assertTrue(facts.path("storyMemory").toString().contains("第一章已确认修补人是旧村工匠"));var out=passage("本次交谈得到了进一步核实。","RESOLVED",60,action(a,"甲比对了另一处实际刻痕。",0,40,List.of()));out.put("continuity","第一章已确认修补人是旧村工匠；刻痕属于修补记号，来历仍待查证。最新已比对第"+mainCalls.get()+"处。");return out;});
  for(int n=0;n<6;n++)assertEquals("COMPLETED",operations.create(request("memory-"+n,"继续调查下一项线索。付出重大代价前询问我。")).get("status"));assertEquals(6,mainCalls.get());assertEquals(12,roleCalls.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:continuity'",Integer.class,sid,player));
 }
 @Test void mechanicalResultReturnsToSameAuthorWithoutRedrawingRoles()throws Exception{
  main(facts->{if(mainCalls.get()==1){var out=json.createObjectNode();out.putArray("mechanics").addObject().put("actorId",player).put("targetId",a).put("kind","ATTACK").put("statKey","DOU");return out;}var contest=facts.path("serverContests").get(0).path("facts");return passage("你与工匠甲短暂交手，双方势均力敌，各自退开，已经承受了实际损伤。","RESOLVED",60,action(player,"你与甲交手，平局后各自退开。",0,30,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",contest.path("outcome").asText(),"fingerprint",contest.path("fingerprint").asText()))));});
  var done=operations.create(request("mechanics","与工匠甲交手。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(2,roleCalls.get());assertEquals(2,mainCalls.get());assertEquals(95,new VitalStateService(jdbc).health(sid,player));assertEquals(95,new VitalStateService(jdbc).health(sid,a));
 }
 @Test void crossLocationDialogueRollsBackAndRecoveryRefreshesTheActualError()throws Exception{
  String to=scene+"_far";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'山径','[]','山径有一块白石。',1,0)",to,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','山径')",sid,scene,to);
  var repaired=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);int count=mainCalls.incrementAndGet();if(repaired.get()){assertFalse(messages.get(messages.size()-1).get("content").contains("OUTDATED_ERROR"));assertTrue(messages.get(messages.size()-1).get("content").contains("同一地点"));}
   var move=action(player,"你沿山径抵达白石边。",0,300,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",to)));
   return response(repaired.get()?passage("你沿山径抵达白石边，旧地点的人留在身后。","RESOLVED",300,move):passage("你已走到白石边，甲仍在石柱旁却对你说：“在这里等我。”","RESOLVED",400,move,speech(a,"在这里等我。",320)));
  }).when(model).chatForJson(anyList());
  var done=operations.create(request("far-speech","沿山径离开。"));assertEquals("PARTIAL",done.get("status"));assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertTrue(payload("far-speech").path("events").isEmpty());assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  jdbc.update("UPDATE game_operation SET payload=JSON_SET(payload,'$.sceneIssue','OUTDATED_ERROR') WHERE session_id=? AND operation_id='far-speech'",sid);repaired.set(true);done=operations.recover(sid,pid,"far-speech",0);assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(2,roleCalls.get());assertEquals(3,mainCalls.get());assertEquals(to,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertFalse(payload("far-speech").path("story").asText().contains("在这里等我"));
 }
 @Test void explorationArrivalRefreshesNewRoleAndCheckpointRestoresTheWholeChapter()throws Exception{
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();if(!Set.of(a,b).contains(role)){assertTrue(own.toString().contains("守亭人私有旧事"));assertFalse(own.toString().contains("仅"+a+"知道的私事"));}roleCalls.incrementAndGet();return response(Map.of("intention","照看自己的地方，愿意回答普通问询","communicationIntent","愿说明山亭可以避雨，重要事情另问"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();
   if(step==1){
    var newcomer=Map.of("name","守亭人","stats",Map.of("zhi",15,"hui",15,"dou",15,"wen",15,"ben",25,"gui",75),"inventory",List.of(),"body",Map.of("awake",true,"bound",false,"position","山亭门口","activity","照看山亭"),"privateBackground","守亭人私有旧事");
    return response(passage("你告别石柱边的两人，沿山径走过湿石坡，来到一处山亭。守亭人立在门口，你走近停步。","CONTINUE",600,action(player,"你沿山径探索并抵达山亭。",0,600,List.of(Map.of("kind","EXPLORE","direction","EAST","exitName","通往山亭的山径","returnRoute",true,"scene",Map.of("locationName","山亭","opening","山亭外雨丝细密，守亭人立在门口。","characters",List.of(newcomer)))))));
   }
   assertEquals("山亭",facts.path("currentWorld").path("location").asText());assertEquals(1,facts.path("roleDirections").size());String keeper=facts.path("roleDirections").fieldNames().next();assertTrue(keeper.startsWith("npc_"));
   if(step==2){assertTrue(facts.path("alreadyHappenedThisChapter").get(0).asText().contains("来到一处山亭"));var out=passage("守亭人看见你在檐下停步，开口道：“亭内能避雨。”你自然问清可歇脚的位置，在门边坐下，得知前面旧道通向渡口。","RESOLVED",180,speech(keeper,"亭内能避雨。",10),action(player,"你在门边坐下避雨。",20,20,List.of(Map.of("kind","BODY","actorId",player,"after",Map.of("position","山亭门边")),Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"))));out.put("continuity","已离开石柱边，沿可返回的山径抵达山亭，守亭人允许普通避雨，得知前方旧道通往渡口。");return response(out);}
   return response(passage("你起身整理衣襟。","RESOLVED",30,action(player,"你起身整理衣襟。",0,20,List.of(Map.of("kind","BODY","actorId",player,"after",Map.of("position","山亭檐下站立"))))));
  }).when(model).chatForJson(anyList());
  var done=operations.create(request("explore-story","向东探索，沿途问询与避雨自然衔接，重大决定前再停。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(780L,done.get("storySeconds"));assertEquals(2,mainCalls.get());assertEquals(3,roleCalls.get());
  String destination=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,destination);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=?",Integer.class,sid));assertEquals(2,payload("explore-story").path("scenePassages").size());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='VISITED_SCENE'",Integer.class,sid,a,destination));
  int savedRound=jdbc.queryForObject("SELECT current_round FROM game_session WHERE session_id=?",Integer.class,sid);operations.create(request("later","起身整理衣襟。"));int paid=mainCalls.get();checkpoints.restore(sid,savedRound);checkpoints.resume(sid,pid);assertEquals(paid,mainCalls.get());assertEquals(destination,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals("山亭门边",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow()).get("position"));assertEquals(780L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertNull(jdbc.queryForObject("SELECT active_operation FROM game_runtime WHERE session_id=?",String.class,sid));assertTrue(knowledge.privateContext(sid,player).contains("普通避雨"));
 }
 @Test void detailedTemplatesArchiveAllPastAndMainCanPageHistoryWithoutRechoosingRoles()throws Exception{
  for(int i=0;i<28;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'亲历',?,'TURN_EVENT','',?,'',?)",sid,a,"old-"+i,json.writeValueAsString(Map.of("kind","ACTION_RESULT","actorId",a,"text",i==0?"OLD_BEYOND_RECENT24：曾亲眼见到渡口断缆":"过去亲历第"+i+"件事情","endSeconds",i)),scene,i);
  var historyRead=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();assertFalse(own.toString().contains(role.equals(a)?b+"私有心理":a+"私有心理"));if(roleCalls.get()>=2)assertTrue(own.path("previousPsychology").toString().contains(role+"私有心理"));roleCalls.incrementAndGet();return response(Map.of("intention","自行回想过往，暂不对外透露","characterTemplate",Map.of("currentPsychology",role+"私有心理","pastExperienceSummary","结合自己真正经历的事回想旧日，尚未对外说出","immediateGoal","在石柱边理清线索")));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));mainCalls.incrementAndGet();assertFalse(facts.has("privateRoleContexts"));assertFalse(facts.has("playerContext"));var templates=facts.path("characterDossiers");assertEquals(3,templates.size());assertEquals("CHARACTER_V2",templates.path(a).path("templateVersion").asText());assertTrue(templates.path(a).path("psychology").toString().contains(a+"私有心理"));assertEquals(facts.path("currentWorld").path("resources").path(a).path("body").path("health"),templates.path(a).path("currentState").path("body").path("health"));assertTrue(templates.path(player).path("psychology").isEmpty());assertTrue(templates.path(player).path("playerDeclaredDirection").asText().contains("观察"));
   if(!historyRead.get()){
    var pages=facts.path("worldBookResults");if(pages.toString().contains("OLD_BEYOND_RECENT24"))historyRead.set(true);
    else {var out=json.createObjectNode();var query=out.putArray("worldBookRequests").addObject().put("roleId",a).put("limit",6);if(pages.isArray() && !pages.isEmpty()){var last=pages.get(pages.size()-1);assertFalse(last.toString().contains(b+"私有心理"));assertFalse(last.path("entries").isEmpty());query.put("beforeEntryId",last.path("nextBeforeEntryId").asLong());}return response(out);}
   }
   var own=action(a,"甲默默回想自己经历过的事，没有说出私有心理。",0,10,List.of());own.put("visibility","PRIVATE");return response(passage("你在石柱边观察片刻，两人各自安静。","RESOLVED",20,own));
  }).when(model).chatForJson(anyList());
  var req=request("template-history","在原地观察，普通事情自然推进。");var done=operations.create(req);assertEquals("COMPLETED",done.get("status"),done.toString());assertTrue(historyRead.get());assertEquals(2,roleCalls.get());assertTrue(mainCalls.get()>2);assertTrue(payload("template-history").path("sceneBookQueries").size()>1);assertFalse(done.toString().contains("私有心理"));
  int archiveCount=jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid);assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER' AND is_secret=true",Integer.class,sid));operations.create(req);assertEquals(archiveCount,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid));
  assertEquals("COMPLETED",operations.create(request("template-next","继续观察。")).get("status"));assertEquals(4,roleCalls.get());checkpoints.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid));
 }
 @Test void recoveryRevalidatesSavedRoleResponsesBeforePayingAgain()throws Exception{
  var fixed=new java.util.concurrent.atomic.AtomicBoolean(false);
  main(facts->passage("你与甲把调查范围谈清。","RESOLVED",60,action(a,"甲说明调查范围。",0,30,fixed.get()?List.of():List.of(Map.of("kind","BODY","actorId",a,"after",Map.of("health",999))))));
  assertEquals("PARTIAL",operations.create(request("role-replay","谈清调查范围。")).get("status"));assertEquals(2,roleCalls.get());
  // Simulate an older serializer having persisted valid raw responses without projecting them.
  jdbc.update("UPDATE game_operation SET payload=JSON_REMOVE(payload,'$.sceneDirections') WHERE session_id=? AND operation_id='role-replay'",sid);
  fixed.set(true);assertEquals("COMPLETED",operations.recover(sid,pid,"role-replay",0).get("status"));
  assertEquals(2,roleCalls.get(),"accepted saved role responses must be replayed locally");assertEquals(3,mainCalls.get());
 }
 @Test void importantAndExtraTemplatesReachMainOnceAndPromotionSurvivesNextTurn()throws Exception{
  var second=new java.util.concurrent.atomic.AtomicBoolean(false);
  var detailed=(ObjectNode)json.readTree("""
   {"selfConcept":{"selfImage":"修柱工匠"},"personality":{"stableTraits":["负责"]},"values":{"moralLines":["不把猜测当证据"]},"emotionalState":{"dominantEmotion":"关切"},"cognition":{"unknowns":["断缆原因"]},"motivations":{"immediateGoal":"弄清旧修补"},"relationships":[],"memoryInterpretation":{"cumulativeHistory":"记得修柱的亲历"},"expression":{"register":"朴实"},"cooperationAndConflict":{"willingParticipation":"愿说明修补方法"},"actionPlan":{"primaryDirection":"完成比对"},"continuity":{"retainedAnchors":["关切真实原因"]}}
   """);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();
   assertTrue(messages.get(0).get("content").contains("EXTRA不填详细"));
   assertEquals(10000,com.bs.narrative.llm.LLMRequestPolicy.current().maxTokens());
   if(second.get()){
    assertEquals(who.equals(a)?"CORE":"EXTRA",own.path("previousPortrayal").path("narrativeTier").asText());
    assertTrue(own.path("previousPsychology").has(who.equals(a)?"values":"roleInScene"));
    assertFalse(own.path("ownRecords").toString().contains("characterTemplate"),"old detailed templates must not grow the role prompt every turn");
   }
   boolean core=who.equals(a)||second.get();var result=json.createObjectNode().put("intention","自然说明已知情况").put("narrativeTier",core?"CORE":"EXTRA").put("tierReason",core?"有持续线索与个人目标":"仅普通守望");
   result.set("characterTemplate",core?detailed.deepCopy():json.createObjectNode().put("roleInScene","普通守望").put("attitude","平和").put("willingHelp","指明可休息位置"));roleCalls.incrementAndGet();return response(result);
  }).when(model).chatSubForJson(anyList());
  main(facts->{var ds=facts.path("characterDossiers");assertEquals(detailed,ds.path(a).path("psychology"));assertEquals(second.get()?"CORE":"EXTRA",ds.path(b).path("narrativeTier").asText());
   assertFalse(ds.path(a).path("intentions").has("characterTemplate"));assertFalse(facts.path("roleDirections").path(a).has("characterTemplate"));
   assertFalse(ds.path(b).path("pastInformation").toString().contains("仅"+a+"知道的私事"));
   return passage("你听两人把各自知道的情形说明白，在石柱旁歇脚。","RESOLVED",60,action(player,"你在石柱旁歇脚。",0,30,List.of()));
  });
  assertEquals("COMPLETED",operations.create(request("tier-first","普通问清现场情况。")).get("status"));second.set(true);
  assertEquals("COMPLETED",operations.create(request("tier-next","继续已知线索的调查。")).get("status"));
  assertEquals(4,roleCalls.get());assertEquals(2,mainCalls.get());
  var saved=payload("tier-next");assertTrue(saved.path("sceneDirections").path("0").path(a).has("characterTemplate"),"input projection must not erase persisted model responses");
 }
 ObjectNode authored(String prose,JsonNode... events){
  var draft=passage(prose,"RESOLVED",300,events);var memories=draft.putArray("roleExperiences");
  memories.addObject().put("roleId",a).put("experience","甲参与了调查，知道刻痕是修补记号；甲私下回忆的独有标记甲隐情。").put("situation","调查已有结论；愿意继续说明，但未结盟。甲隐情仅自己知道。");
  memories.addObject().put("roleId",b).put("experience","乙听到刻痕是修补记号，没有听见甲的私有回忆。").put("situation","仍在守望，知道调查结论，未作新承诺。");
  return draft;
 }
 @Test void storyFirstProseOnlyPersistsPersonalContinuityWithoutMicroEvents()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  var next=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();
   assertFalse(own.has("ownItemPlacements"));assertFalse(own.has("activeWork"));assertTrue(own.path("unfinishedIntentions").isEmpty());assertFalse(own.path("body").has("position"));
   if(next.get()){
    assertTrue(own.path("ownRecords").toString().contains("CHAPTER_EXPERIENCE"));assertTrue(own.path("storySituation").asText().contains(who.equals(a)?"未结盟":"未作新承诺"));
    assertEquals(who.equals(a),own.toString().contains("甲隐情"));
   }
   roleCalls.incrementAndGet();return response(Map.of("intention","承接已经确认的修补线索，不重演调查"));
  }).when(model).chatSubForJson(anyList());
  main(facts->{assertTrue(facts.path("storyFirst").asBoolean());assertFalse(facts.path("currentWorld").has("gatherableResources"));assertFalse(facts.path("currentWorld").path("resources").path(a).path("body").has("position"));
   if(next.get())assertTrue(facts.path("characterDossiers").path(a).path("currentState").path("storySituation").asText().contains("未结盟"));
   return authored(next.get()?"你顺着已经查明的修补线索问清了当年的来人，得知他后来去了渡口。":"你问清两人的所知，又比对修补痕迹，确认这里留下的是旧修补记号；甲的私有往事仍没有说出。");});
  assertEquals("COMPLETED",operations.create(request("prose-first","自然问清经过并形成结论。")).get("status"));next.set(true);
  assertEquals("COMPLETED",operations.create(request("prose-next","顺着结论问清下一条线索。")).get("status"));
  assertEquals(4,roleCalls.get());assertEquals(2,mainCalls.get());assertTrue(payload("prose-next").path("events").isEmpty());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_PLACEMENT'",Integer.class,sid));
  assertFalse(knowledge.privateContext(sid,b).contains("甲隐情"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='CHARACTER_HISTORY' AND is_secret=false",Integer.class,sid));
 }
 @Test void storyFirstSavesNarrativeResultsWithoutVerbOrMaterialWhitelistAndRestoresTogether()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String ref=items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  main(facts->authored("救援结束。甲的短刀已损毁，村人以一枚渡符作谢礼。调查也证实渡口昨夜封闭，甲受伤后只剩七成体力。",action(a,"救援已有结果与回报。",0,0,List.of(
   Map.of("kind","USE_UP","itemRef",ref),Map.of("kind","GAIN","name","渡符","source","村人因救援赠予的谢礼"),
   Map.of("kind","FACT","objectName","渡口封闭的调查结论","after","已由实际调查证实昨夜封闭，原因待查"),Map.of("kind","HEALTH","actorId",a,"health",70)))));
  var done=operations.create(request("durable","把救援调查推进到有结果。"));assertEquals("COMPLETED",done.get("status"),done.toString());
  assertEquals(List.of("渡符"),actors.findById(a).orElseThrow().getInventory());assertEquals(70,new VitalStateService(jdbc).health(sid,a));assertTrue(temporal.sceneState(sid,scene).toString().contains("昨夜封闭"));
  assertTrue(jdbc.queryForList("SELECT event_description FROM world_book WHERE session_id=? AND event_type='CHARACTER_HISTORY'",String.class,sid).toString().contains("situation"));
  checkpoints.restore(sid,0);assertEquals(List.of("短刀"),actors.findById(a).orElseThrow().getInventory());assertEquals(100,new VitalStateService(jdbc).health(sid,a));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ROLE_STORY_STATE'",Integer.class,sid));assertTrue(temporal.sceneState(sid,scene).isEmpty());
 }
 @Test void storyFirstIgnoresGestureBookkeepingAndIntentionDoesNotTransfer()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String ref=items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  doAnswer(call->{roleCalls.incrementAndGet();return response(Map.of("intention","先说明借用条件，待双方确认","itemIntentions",List.of(Map.of("itemRef",ref,"intention","借出","targetId",b,"conditions","查完归还"))));}).when(model).chatSubForJson(anyList());
  main(facts->{assertFalse(facts.path("characterDossiers").path(a).path("intentions").path("itemIntentions").isEmpty());return authored("甲把短刀换到另一只手，说明愿意借出，但尚未交付。",action(a,"甲换手并整理衣角。",0,0,List.of(Map.of("kind","ITEM_POSITION","itemRef",ref,"after","另一只手"),Map.of("kind","BODY","actorId",a,"after",Map.of("position","侧身","activity","整理衣角")))));});
  assertEquals("COMPLETED",operations.create(request("intent","先问清借用条件。")).get("status"));assertEquals(a,items.owned(sid,actors.findById(a).orElseThrow()).get(0).ownerId());assertTrue(actors.findById(b).orElseThrow().getInventory().isEmpty());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_PLACEMENT'",Integer.class,sid));
  main(facts->authored("双方同意查完归还，甲实际将短刀借给乙。",action(a,"甲交刀给乙。",0,0,List.of(Map.of("kind","TRANSFER","fromId",a,"toId",b,"itemRef",ref,"mode","LEND")))));
  assertEquals("COMPLETED",operations.create(request("lend","照双方意愿办理借用。")).get("status"));assertEquals(ref,items.owned(sid,actors.findById(b).orElseThrow()).get(0).ref());assertEquals(a,ItemStateService.legalOwner(items.owned(sid,actors.findById(b).orElseThrow()).get(0)));
  main(facts->authored("调查结束，乙把借来的短刀原样归还给甲。",action(b,"乙实际归还短刀。",0,0,List.of(Map.of("kind","TRANSFER","fromId",b,"toId",a,"itemRef",ref,"mode","RETURN")))));
  assertEquals("COMPLETED",operations.create(request("return","完成调查并归还借物。")).get("status"));assertEquals(ref,items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref());assertTrue(actors.findById(b).orElseThrow().getInventory().isEmpty());
 }
 @Test void storyFirstSceneCustodyKeepsStableIdentityAndPersonalMemoryLocation()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String ref=items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref();
  main(facts->authored("甲把短刀留在石柱边。",action(a,"甲留下短刀。",0,0,List.of(Map.of("kind","DROP","itemRef",ref)))));
  assertEquals("COMPLETED",operations.create(request("drop","先留下工具。")).get("status"));assertTrue(actors.findById(a).orElseThrow().getInventory().isEmpty());assertEquals(ref,items.sceneItems(sid,scene).get(0).get("ref"));
  main(facts->{assertEquals(ref,facts.path("currentWorld").path("sceneItems").get(0).path("ref").asText());return authored("甲回来取回留在这里的短刀。",action(a,"甲取回工具。",0,0,List.of(Map.of("kind","PICKUP","itemRef",ref))));});
  assertEquals("COMPLETED",operations.create(request("pickup","取回留下的工具。")).get("status"));assertTrue(items.sceneItems(sid,scene).isEmpty());assertEquals(ref,items.owned(sid,actors.findById(a).orElseThrow()).get(0).ref());
  String to=scene+"_next";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'渡口','[]','远处渡口。',1,0)",to,sid);jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','渡口')",sid,scene,to);
  main(facts->authored("你前往渡口，甲乙留在石柱边。",action(player,"你沿路抵达渡口。",0,100,List.of(Map.of("kind","MOVE","actorId",player,"fromSceneId",scene,"toSceneId",to)))));
  assertEquals("COMPLETED",operations.create(request("leave","自己前往渡口。")).get("status"));
  assertEquals(scene,jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story-experience:leave:0'",String.class,sid,a));
 }
 @Test void storyFirstRejectsForeignMemoryAndRollsBackWholeResultWithoutRedrawingRoles()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var draft=authored("两人查清了昨夜的来客。",action(a,"调查得出结论。",0,0,List.of(Map.of("kind","FACT","objectName","昨夜来客","after","修柱人来过"))));
   if(mainCalls.get()==1)((ObjectNode)draft.path("roleExperiences").get(1)).put("roleId","outside_this_save");return draft;});
  assertEquals("COMPLETED",operations.create(request("repair-memory","完成来客调查。")).get("status"));assertEquals(2,mainCalls.get());assertEquals(2,roleCalls.get());
  assertEquals(1,payload("repair-memory").path("scenePassages").size());assertEquals(1,temporal.sceneState(sid,scene).size());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story-experience:repair-memory:0'",Integer.class,sid,player));
 }
 @Test void storyFirstImportantChoiceStillStopsBeforeConsent()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var draft=authored("调查已有结果，甲提出与你结盟，等你决定。",speech(a,"你是否愿意与我结盟？",200));draft.put("stop","DECISION");draft.putObject("decision").put("type","ALLIANCE").put("eventIndex",0).put("question","是否接受甲的结盟提议？");return draft;});
  var done=operations.create(request("story-choice","调查清楚后再作打算。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertTrue(payload("story-choice").path("storyStop").path("requiresInput").asBoolean());assertFalse(payload("story-choice").path("story").asText().contains("你答应"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type IN ('PROMISE','TREATY')",Integer.class,sid),"关键词不得再建立另一套决定人物关系的状态");
 }
 @Test void storyFirstExplorationMovesWillingCompanionsAndRefreshesRolesWithoutPoseRequirements()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));assertFalse(own.path("body").has("position"));roleCalls.incrementAndGet();return response(Map.of("intention","同行到渡口，说明本人知道的线索"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var facts=json.readTree(messages.get(1).get("content"));int step=mainCalls.incrementAndGet();ObjectNode draft;
   if(step==1){
    var expansion=json.createObjectNode().put("kind","EXPLORE").put("direction","EAST").put("exitName","通往渡口").put("returnRoute",true);
    var place=expansion.putObject("scene").put("locationName","渡口").put("opening","一名船夫在岸边等候。");var man=place.putArray("characters").addObject().put("name","船夫").put("privateBackground","自己熟悉渡口水情");
    man.set("stats",json.valueToTree(Map.of("zhi",15,"hui",15,"dou",15,"wen",15,"ben",25,"gui",75)));man.putArray("inventory");man.putObject("body").put("awake",true).put("bound",false);
    draft=passage("你与甲乙沿路抵达渡口。","CONTINUE",100,action(player,"你沿东路抵达渡口。",0,100,List.of(expansion)),action(a,"甲同行抵达渡口。",0,100,List.of(Map.of("kind","MOVE","actorId",a,"fromSceneId",scene,"toSceneId","NEW_DESTINATION"))),action(b,"乙同行抵达渡口。",0,100,List.of(Map.of("kind","MOVE","actorId",b,"fromSceneId",scene,"toSceneId","NEW_DESTINATION"))));
   }else{
    assertEquals(2,step);assertEquals(3,facts.path("roleDirections").size());assertEquals("渡口",facts.path("currentWorld").path("location").asText());assertFalse(facts.path("alreadyHappenedThisChapter").isEmpty());
    draft=passage("抵达之后，你们向船夫问清水情，得知昨日停渡是因为上游落木，今早已清理，可在午后来船时渡河。","RESOLVED",300);
   }
   var memories=draft.putArray("roleExperiences");facts.path("roleDirections").fieldNames().forEachRemaining(who->memories.addObject().put("roleId",who).put("experience",step==1?"实际同行抵达渡口。":"参与询问并知道午后可以渡河。").put("situation",step==1?"现已抵达渡口，原调查结论保留。":"已在渡口问清停渡原因和午后来船线索，尚未渡河。"));return response(draft);
  }).when(model).chatForJson(anyList());
  var done=operations.create(request("explore-together","沿东路同行到渡口，自然问清能否渡河。")).get("status");assertEquals("COMPLETED",done,payload("explore-together").toString());assertEquals(5,roleCalls.get());assertEquals(2,mainCalls.get());
  String here=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,here);assertTrue(scenes.findBySessionIdAndSceneId(sid,here).orElseThrow().getPresentGods().containsAll(List.of(a,b)));assertTrue(scenes.findBySessionIdAndSceneId(sid,scene).orElseThrow().getPresentGods().isEmpty());
  assertEquals(here,jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story-experience:explore-together:0'",String.class,sid,a));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_PLACEMENT'",Integer.class,sid));
 }
 @Test void storyFirstAuthorChoosesAccidentSeparationAndCarriedArrivalWithoutRouteOrBodyGates()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String to=scene+"_shelter";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'避雨棚','[]','山路边的避雨棚。',8,8)",to,sid);
  // There is no registered route. Both role APIs still propose the same willing participation.
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var facts=json.readTree(messages.get(1).get("content"));int step=mainCalls.incrementAndGet();ObjectNode draft;
   if(step==1){
    assertEquals(2,facts.path("roleDirections").size());draft=passage("众人本想同行，突遇落木。甲先到避雨棚，乙受伤被压住，失去意识。你留下救乙，尚未抵达。","RESOLVED",200,
     action(a,"甲先抵达避雨棚。",0,100,List.of(Map.of("kind","MOVE","actorId",a,"fromSceneId",scene,"toSceneId",to))),
     action(player,"落木压住乙，乙受伤昏迷，你留在他身旁。",100,0,List.of(Map.of("kind","HEALTH","actorId",b,"health",65),Map.of("kind","BODY","actorId",b,"after",Map.of("awake",false,"bound",true)))));
    var memories=draft.putArray("roleExperiences");memories.addObject().put("roleId",a).put("experience","先抵达避雨棚，尚不知道身后落木的详细结果。").put("situation","独自在避雨棚，同行者尚未到来。");memories.addObject().put("roleId",b).put("experience","在途中被落木压住，疼痛后失去意识。").put("situation","受伤昏迷，不能知道昏迷期间发生的经过。");
   }else{
    assertEquals(1,facts.path("roleDirections").size());assertTrue(facts.path("characterDossiers").has(b));assertFalse(facts.path("currentWorld").path("resources").path(b).path("body").path("awake").asBoolean());
    draft=passage("你将乙连同暂时无法解开的缠枝一起抬到避雨棚。他仍昏迷，伤势没有凭空好转；这次救援已经把人带离危险。","RESOLVED",200,
     action(player,"你将仍昏迷、被缠枝困住的乙抬到避雨棚。",0,200,List.of(Map.of("kind","MOVE","actorId",b,"toSceneId",to),Map.of("kind","MOVE","actorId",player,"toSceneId",to))));draft.putArray("roleExperiences");
   }
   return response(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("accident","大家照各自意愿同行。")).get("status"));
  assertTrue(scenes.findBySessionIdAndSceneId(sid,to).orElseThrow().getPresentGods().contains(a));assertFalse(scenes.findBySessionIdAndSceneId(sid,to).orElseThrow().getPresentGods().contains(b));assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
  assertEquals("COMPLETED",operations.create(request("carry","把仍受困昏迷的乙抬到避雨棚，不替他恢复意识或伤势。")).get("status"));
  assertEquals(to,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertTrue(scenes.findBySessionIdAndSceneId(sid,to).orElseThrow().getPresentGods().containsAll(List.of(a,b)));assertEquals(65,new VitalStateService(jdbc).health(sid,b));
  assertFalse((Boolean)physical.view(sid,actors.findById(b).orElseThrow(),scenes.findBySessionIdAndSceneId(sid,to).orElseThrow()).get("awake"));assertEquals(3,roleCalls.get());assertEquals(2,mainCalls.get());
 }
 @Test void storyFirstNpcCanActuallyExploreWhilePlayerAndOthersStayBehind()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var expansion=json.createObjectNode().put("kind","EXPLORE").put("direction","EAST").put("exitName","林间小径").put("returnRoute",true);expansion.putObject("scene").put("locationName","林间小径").put("opening","小径深处有一座无人的旧棚。").putArray("characters");
   var departure=action(a,"甲独自探路，抵达林间小径并发现旧棚。",0,100,List.of(expansion));departure.put("visibility","PRIVATE").putArray("observations");
   var draft=passage("甲决定先探路，随后消失在林间。你与乙仍留在石柱旁，并不知道他在里面发现了什么。","RESOLVED",100,departure);
   var memories=draft.putArray("roleExperiences");memories.addObject().put("roleId",a).put("experience","独自到达林间小径，发现无人旧棚。").put("situation","独自在小径，尚未回去告诉玩家发现。");memories.addObject().put("roleId",b).put("experience","看见甲独自离开，未见小径里的经过。").put("situation","仍在石柱边，不知道甲的探路结果。");return draft;});
  assertEquals("COMPLETED",operations.create(request("npc-explore","各自按意愿行动，探路结果回来再讲。")).get("status"));
  String there=physical.actorScene(sid,a).orElseThrow().getSceneId();assertNotEquals(scene,there);assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(1,mainCalls.get());
  assertFalse(payload("npc-explore").path("events").toString().contains("发现旧棚"));
 }
 @Test void storyFirstAcceptsDirectResultsWithoutMicroTimingAndReusesExistingCompanionIdentity()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var draft=authored("你和甲抵达林隙，乙留在原地。");var results=draft.putArray("events");
   var creation=results.addObject().put("kind","EXPLORE").put("direction","EAST").put("exitName","林隙").put("returnRoute",true);
   var place=creation.putObject("scene").put("locationName","林隙").put("opening","林隙里光线渐亮。");
   // Provider echoed a known companion in scene.characters; this must never clone that person.
   place.putArray("characters").addObject().put("name","工匠甲");
   results.addObject().put("kind","MOVE").put("actorId",player).put("toSceneId","NEW_DESTINATION");results.addObject().put("kind","MOVE").put("actorId",a).put("toSceneId","NEW_DESTINATION");return draft;});
  var done=operations.create(request("flat-results","按各自意愿前往林隙。"));assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(1,mainCalls.get());assertEquals(2,roleCalls.get());
  String here=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,here);assertEquals(List.of(a),scenes.findBySessionIdAndSceneId(sid,here).orElseThrow().getPresentGods());assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='工匠甲'",Integer.class,sid));
  assertEquals("SCENE_DEFINED",payload("flat-results").path("events").get(0).path("effects").get(0).path("kind").asText(),"地点资料创建不能伪装成任何人的自动移动");
 }
 @Test void storyFirstMainIntroducesBriefExtraThenRoleApiSuppliesOwnIntentionsBeforeFurtherConversation()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var newRole=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();
   if(!List.of(a,b).contains(who)){newRole.set(who);assertTrue(own.path("establishedSelf").toString().contains("老汉私有账本"));assertTrue(own.path("storySituation").asText().contains("拟初次出场"));assertTrue(own.path("ownRecords").isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='守屋老汉'",Integer.class,sid));}else assertFalse(own.toString().contains("老汉私有账本"));
   roleCalls.incrementAndGet();return response(Map.of("intention","只说明愿意公开的草器与旧物线索","narrativeTier","EXTRA","characterTemplate",Map.of("roleInScene","普通守屋者","attitude","平和")));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var facts=json.readTree(messages.get(1).get("content"));int step=mainCalls.incrementAndGet();
   var draft=passage(step==1?"你们走近草棚，守屋老汉放下扫帚，抬头看向来人。":"老汉按自己愿意说明的范围，讲清早年的货郎与旧石线索；你们得到了可继续核实的方向。",step==1?"CONTINUE":"RESOLVED",100);
   draft.putArray("newCharacters").addObject().put("name","守屋老汉").put("profile","在草棚编草器换盐米，老汉私有账本只有本人知道。").put("narrativeTier","EXTRA").put("situation","独居棚下，刚看见几名来人。").put("experience","在棚下编扫帚，见玩家与同行者靠近。");
   if(step>1){assertEquals(3,facts.path("roleDirections").size());assertTrue(facts.path("roleDirections").has(newRole.get()));}
   var memories=draft.putArray("roleExperiences");facts.path("roleDirections").fieldNames().forEachRemaining(who->memories.addObject().put("roleId",who).put("experience",step==1?"看见棚下老汉，尚未深入交谈。":"在棚下听到实际公开的旧物线索。").put("situation","在棚下，保留各自已经确立的身份、关系与秘密。"));return response(draft);
  }).when(model).chatForJson(anyList());
  var done=operations.create(request("brief-cast","去草棚问清旧物线索。")).get("status");assertEquals("COMPLETED",done,payload("brief-cast").toString());assertEquals(3,roleCalls.get());assertEquals(2,mainCalls.get());assertNotNull(newRole.get());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='守屋老汉'",Integer.class,sid));assertEquals(1,payload("brief-cast").path("scenePassages").size());
  checkpoints.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='守屋老汉'",Integer.class,sid));
 }
 @Test void storyOutcomesTravelMeetQuestionAndNextOperationContinueFromSavedResults()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var shopkeeper=new java.util.concurrent.atomic.AtomicReference<String>();var knife=items.custody(sid,actors.findById(a).orElseThrow()).get(0).get("ref").toString();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String who=own.path("id").asText();
   if(!List.of(a,b).contains(who)){shopkeeper.set(who);assertTrue(own.path("establishedSelf").toString().contains("店主私有旧账"));}else assertFalse(own.toString().contains("店主私有旧账"));
   roleCalls.incrementAndGet();return response(Map.of("intention","自然说明本人愿意公开的线索","narrativeTier","EXTRA","characterTemplate",Map.of("roleInScene","本段参与者")));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();
   var d=passage(step==1?"你和甲抵达栎坡杂货铺，乙留在石柱边。店主抬头招呼来客。":step==2?"店主找出账本，与甲和你核对，确认货郎的去向。这次打听得到了具体结果。":"店主记得刚才核对的去向，又说明了那人的近况，问你愿不愿一起查下去，结成盟约。",step==1?"CONTINUE":step==2?"RESOLVED":"DECISION",step==1?3600:300);
   var outcomes=d.putArray("sceneOutcomes");
   if(step<=2){var destination=outcomes.addObject();destination.putObject("scene").put("locationName","栎坡杂货铺").put("opening","井台旁的杂货铺，店主在柜台后。");var arrivals=destination.putArray("roleIds").add(player).add(a);if(step==2){arrivals.add(shopkeeper.get());assertEquals(3,f.path("roleDirections").size());assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));}outcomes.addObject().put("sceneId",scene).putArray("roleIds").add(b);
    d.putArray("newCharacters").addObject().put("name","店主").put("profile","守着店铺，店主私有旧账尚未对外公开。").put("sceneId","").put("situation","刚见到来问路的玩家与甲。").put("experience","在柜台后见到两位来客。");
    d.withArray("events").addObject().put("kind","FACT").put("objectName","杂货铺").put("after","仍在营业，已经见到店主").put("atSeconds",3000).put("durationSeconds",3400);
    d.withArray("events").addObject().put("kind","DROP").put("actorId",a).put("sceneId",scene).put("itemRef",knife);if(step==2)d.withArray("events").addObject().put("kind","FACT").put("objectName","货郎去向").put("after","店主依账本核对，货郎交货去南桥镇。");
   }else{
    assertFalse(f.path("roleDirections").has(b));assertTrue(f.path("roleDirections").has(shopkeeper.get()));assertEquals(2,f.path("roleDirections").size());
    outcomes.addObject().put("sceneId",f.path("currentWorld").path("sceneId").asText()).putArray("roleIds").add(player).add(a).add(shopkeeper.get());
    if(step==2)d.withArray("events").addObject().put("kind","FACT").put("objectName","货郎去向").put("after","店主依账本核对，货郎交货去南桥镇。");
    else{assertTrue(f.path("characterDossiers").path(shopkeeper.get()).path("currentState").path("storySituation").asText().contains("南桥镇"));d.withArray("events").add(speech(shopkeeper.get(),"是否结盟，一同查下去？",9999));d.putObject("decision").put("type","ALLIANCE").put("eventIndex",0).put("question","是否接受店主的结盟邀请？");}
   }
   var memories=d.putArray("roleExperiences");f.path("roleDirections").fieldNames().forEachRemaining(who->memories.addObject().put("roleId",who).put("experience",step<=2?who.equals(b)?"看见两人离开，留在石柱边。":step==1?"一路抵达店铺，见到店主。":"在店铺核对账本，知道货郎交货去南桥镇。":"在店铺核对账本，知道货郎交货去南桥镇。").put("situation",step<=2?who.equals(b)?"仍在石柱边，不知道店铺内情。":step==1?"已在栎坡杂货铺。":"在栎坡杂货铺，已知货郎交货去南桥镇。":"在栎坡杂货铺，已知货郎交货去南桥镇。"));return response(d);
  }).when(model).chatForJson(anyList());
  var req=request("outcome-travel","前往栎坡，问清货郎去向。");assertEquals("COMPLETED",operations.create(req).get("status"),payload("outcome-travel").toString());assertEquals(2,mainCalls.get());assertEquals(3,roleCalls.get());
  String there=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,there);assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(there,physical.actorScene(sid,a).orElseThrow().getSceneId());assertEquals(there,physical.actorScene(sid,shopkeeper.get()).orElseThrow().getSceneId());
  assertTrue(items.sceneItems(sid,scene).toString().contains(knife));assertTrue(items.sceneItems(sid,there).isEmpty());assertTrue(temporal.sceneState(sid,there).toString().contains("南桥镇"));assertFalse(temporal.sceneState(sid,scene).toString().contains("南桥镇"));
  operations.read(sid,pid,"outcome-travel",0L);assertEquals(2,mainCalls.get());assertEquals(1,payload("outcome-travel").path("scenePassages").size());
  assertEquals("COMPLETED",operations.create(request("outcome-next","接着问清货郎的近况，重大决定等我表态。")).get("status"));assertEquals(3,mainCalls.get());assertEquals(5,roleCalls.get());assertTrue(payload("outcome-next").path("storyStop").path("requiresInput").asBoolean());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY'",Integer.class,sid));
 }
 @Test void authoredExactSpeechIsDecidedByMainEvenWhenPlayerUnconscious()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','状态','{\"awake\":false,\"bound\":true}','BODY_STATE','',?,'',0)",sid,player,scene);
  String original="先别靠近，我想听你把事情说完。";
  main(f->{assertEquals("AUTHOR_DECIDES",f.path("playerUtterance").path("delivery").asText());assertEquals(original,f.path("playerUtterance").path("speech").asText());assertTrue(f.path("alreadyCommittedEvents").isEmpty(),"Program must not deliver speech before the author decides what happens");var d=authored("你在同伴的照看下缓缓醒来，挣开已经松动的绳索，随后把话说给甲听。他听完，说明了事情的原委。");d.withArray("events").addObject().put("kind","BODY").put("actorId",player).putObject("after").put("awake",true).put("bound",false);return d;});
  var req=request("authored-speech",original);var talk=new ConversationOperationService.Request(req.operationId(),sid,pid,scene,original,new com.bs.narrative.dto.ActionRequest.DialogueInput(a,original),req.branchEpoch(),req.expectedRevision(),true);
  assertEquals("COMPLETED",operations.create(talk).get("status"));assertTrue((Boolean)physical.view(sid,actors.findById(player).orElseThrow(),scenes.findBySessionIdAndSceneId(sid,scene).orElseThrow()).get("awake"));
 }
 @Test void authoredEnvironmentalChoiceCanStopWithoutAnNpcSpeechAndLongPassageCanSave()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(f->{var d=authored("你们沿河走了两天，前方洪水已漫上桥面。是否冒险过桥，需要你自己决定。").put("elapsedSeconds",172800).put("stop","DECISION");d.putObject("decision").put("type","RISK").put("question","冒险过桥，还是另寻道路？");return d;});
  assertEquals("COMPLETED",operations.create(request("authored-risk","沿河寻找过桥的路，重大风险前让我决定。")).get("status"));assertEquals(172800L,payload("authored-risk").path("endSeconds").asLong());assertTrue(payload("authored-risk").path("storyStop").path("requiresInput").asBoolean());assertTrue(payload("authored-risk").path("events").isEmpty());
 }
 @Test void authoredMechanicalComparisonUsesStatsWithoutSceneOrConsciousnessPermission()throws Exception{
  String other=scene+"_other";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处','[]','另一处地点',8,8)",other,sid);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','状态','{\"awake\":false,\"bound\":true}','BODY_STATE','',?,'',0)",sid,b,scene);
  var packet=json.createObjectNode().put("kind","ATTACK").put("targetId",b).put("statKey","DOU");var comparison=conflicts.proposal(sid,scenes.findBySessionIdAndSceneId(sid,other).orElseThrow(),a,player,packet,true);assertEquals("RESOLVED",comparison.get("status"));
  var effect=json.createObjectNode().put("targetId",b).put("outcome",comparison.get("outcome").toString()).put("fingerprint",comparison.get("fingerprint").toString());conflicts.apply(sid,scenes.findBySessionIdAndSceneId(sid,other).orElseThrow(),a,player,effect,new HashSet<>(),true);
  assertEquals(100,new VitalStateService(jdbc).health(sid,b),"authored victory does not impose an injury");
 }
 @Test void authoredNewPlaceDoesNotRequireRoutePermissionSixStatsPosesOrFourPersonLimit()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(f->{var d=authored("你抵达一间小铺，柜台前有六位茶客，各自忙着自己的事。");var e=d.withArray("events").addObject().put("kind","EXPLORE").put("actorId",player);var place=e.putObject("scene").put("locationName","小铺").put("opening","一间小铺，柜台前有六位茶客。");var people=place.putArray("characters");for(int i=0;i<6;i++)people.addObject().put("name","茶客"+i);return d;});
  assertEquals("COMPLETED",operations.create(request("authored-open-place","继续沿路行走，看看会遇见什么。")).get("status"));String there=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertEquals(6,scenes.findBySessionIdAndSceneId(sid,there).orElseThrow().getPresentGods().size());
 }
 @Test void unusedCastProposalLeavesNoPersonMemoryOrPublicStoryBehind()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var proposed=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String who=own.path("id").asText();if(!List.of(a,b).contains(who)){proposed.set(who);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,who));}roleCalls.incrementAndGet();return response(Map.of("intention","不打算接近来客，继续做自己的事"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{int step=mainCalls.incrementAndGet();var d=authored(step==1?"候选：一名路人可能出现。":"你与甲乙核对旧刻痕，取得了新的比对结果；这段时间没有其他人靠近。");if(step==1)d.putArray("newCharacters").addObject().put("name","候选路人").put("profile","独自忙于自己的事情，不愿凑近人群。");else d.putArray("newCharacters");return response(d);}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("discard-cast","把刻痕调查清楚，自然处理遇见的人。")).get("status"));assertNotNull(proposed.get());assertEquals(3,roleCalls.get());assertEquals(2,mainCalls.get());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='候选路人'",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=?",Integer.class,sid,proposed.get()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND JSON_CONTAINS(actor_ids,JSON_QUOTE(?))",Integer.class,sid,proposed.get()));assertFalse(payload("discard-cast").path("story").asText().contains("候选："));
 }
 @Test void stagedCastKeepsAllPlanningSeparateAndFinalChapterStartsAtCommittedScene()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var newcomers=new ArrayList<String>();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();if(!List.of(a,b).contains(who)){newcomers.add(who);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,who));}roleCalls.incrementAndGet();return response(Map.of("intention","按本人已知情况自然回应"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var f=json.readTree(messages.get(1).get("content"));int step=mainCalls.incrementAndGet();
   assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertTrue(f.path("alreadyHappenedThisChapter").isEmpty());assertFalse(f.toString().contains("UNCOMMITTED_PREVIEW_SENTINEL"));
   var d=step<3?json.createObjectNode():authored("你与甲从石柱边出发，走了两日抵达集市。店主和桥头老汉各按亲历说明旧货去向，核对后得到了可继续追查的线索。").put("elapsedSeconds",172800);var cast=d.putArray("newCharacters");
   cast.addObject().put("name","集市店主").put("profile","店主谨慎，知道旧货线索").put("experience","在集市与来客核对旧货去向");
   if(step==2){assertEquals(1,f.at("/castPlanning/candidates").size());d.put("story","UNCOMMITTED_PREVIEW_SENTINEL");cast.addObject().put("name","桥头老汉").put("profile","老汉卖针线，知道当年亲历");}
   if(step==3){assertEquals(2,f.at("/castPlanning/candidates").size());assertEquals(4,f.path("roleDirections").size());cast.addObject().put("name","桥头老汉").put("profile","老汉卖针线，知道当年亲历").put("experience","说明本人当年见闻");var dest=d.putArray("sceneOutcomes").addObject();dest.putObject("scene").put("locationName","集市").put("opening","石桥两头摆着货摊");var ids=dest.putArray("roleIds").add(player).add(a);newcomers.forEach(ids::add);var memories=d.putArray("roleExperiences");memories.addObject().put("roleId",a).put("experience","两日同行抵达集市，核对旧货去向").put("situation","已到集市，取得线索");}
   return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("planning-whole","前往集市问清旧货去向")).get("status"),payload("planning-whole").toString());assertEquals(3,mainCalls.get());assertEquals(4,roleCalls.get());assertEquals(1,payload("planning-whole").path("scenePassages").size());assertEquals(172800L,payload("planning-whole").path("endSeconds").asLong());
  assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref LIKE 'story-experience:planning-whole:%'",Integer.class,sid,b));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name IN ('集市店主','桥头老汉')",Integer.class,sid));
 }
 @Test void offScenePersonCanReturnToCurrentPlayerSceneWithoutRoutePermission()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处',?,'远处草棚',3,3)",far,sid,json.writeValueAsString(List.of(b)));jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(step==1?1:2,f.path("roleDirections").size());var d=authored("乙从远处回到石柱边，向你和甲说明探路结果。");d.putArray("sceneOutcomes").addObject().put("sceneId",scene).putArray("roleIds").add(b);d.putArray("roleExperiences").addObject().put("roleId",b).put("experience","从草棚返回石柱边，报告探路结果").put("situation","已与玩家和甲会合");return response(d);}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("return-here","等乙回来听探路结果")).get("status"));assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertFalse(scenes.findBySessionIdAndSceneId(sid,far).orElseThrow().getPresentGods().contains(b));assertEquals(2,mainCalls.get());assertEquals(2,roleCalls.get());
 }
 @Test void provisionalCharacterMechanicsDoesNotRequireAnUncommittedDatabaseActor()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var stranger=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();assertEquals(player,own.path("narrativeActorId").asText());assertEquals("NEW_PASSAGE",own.at("/narrativeScope/mode").asText());if(!List.of(a,b).contains(who))stranger.set(who);roleCalls.incrementAndGet();return response(Map.of("intention","愿意按事先说定的规矩点到为止切磋"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var f=json.readTree(messages.get(1).get("content"));int step=mainCalls.incrementAndGet();var d=step<3?json.createObjectNode():authored("路人先说明规矩，你与他点到为止切磋了几招，双方随即停手。");
   if(step==2){d.putArray("mechanics").addObject().put("actorId",player).put("targetId",stranger.get()).put("kind","ATTACK").put("statKey","DOU");}
   else{d.putArray("newCharacters").addObject().put("name","切磋路人").put("profile","懂拳脚，愿意点到为止切磋").put("experience","与来客简短切磋后停手");}
   if(step==3){assertEquals("NARRATIVE",f.at("/serverContests/0/facts/status").asText());d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",99);}
   return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("provisional-contest","若遇到愿意的人，就与其点到为止切磋")).get("status"),payload("provisional-contest").toString());assertEquals(3,mainCalls.get());assertEquals(3,roleCalls.get());assertEquals(99,new VitalStateService(jdbc).health(sid,player));assertTrue(actors.findById(stranger.get()).isPresent());
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void realMarketResponseStoresOneAuthoritativeDestinationAndReusesSelectedCast(boolean omittedRepeatedIntroduction)throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String old=b+"_old";jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,'卧病老掌柜','MORTAL','平静',15,15,15,15,0,50,'[]')",old,sid);jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a,b,old)),scene);
  var newcomer=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));String who=own.path("id").asText();if(!List.of(a,b,old).contains(who))newcomer.set(who);roleCalls.incrementAndGet();return response(Map.of("intention","照实说明本人知道的旧事，不做担保"));}).when(model).chatSubForJson(anyList());
  String fixture;try(var input=getClass().getResourceAsStream("/story-real-market-response.json")){fixture=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  String captured=fixture;
  doAnswer(call->{int step=mainCalls.incrementAndGet();if(step==1){var plan=json.createObjectNode();plan.putArray("newCharacters").addObject().put("name","岳牙人").put("profile","蛇桥集牙人，认得周姓货郎，谨慎，不替未知去向作保");return response(plan);}
   var d=(ObjectNode)json.readTree(captured.replace("FIXTURE_PLAYER",player).replace("FIXTURE_COMPANION",a).replace("FIXTURE_SHOPKEEPER",b).replace("FIXTURE_OLDMAN",old).replace("FIXTURE_NEW_ACTOR",newcomer.get()).replace("FIXTURE_DEPARTURE",scene));
   if(omittedRepeatedIntroduction){var intro=d.path("newCharacters").get(0);d.withArray("roleExperiences").addObject().put("roleId",newcomer.get()).put("experience",intro.path("experience").asText()).put("situation",intro.path("situation").asText());d.putArray("newCharacters");}
   return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("real-market","向老童和铺主问清市集路线，前往市集寻找岳牙人，问清货郎去向")).get("status"),payload("real-market").toString());
  String current=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,current);assertEquals(current,physical.actorScene(sid,newcomer.get()).orElseThrow().getSceneId());assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(scene,physical.actorScene(sid,old).orElseThrow().getSceneId());assertEquals(2,mainCalls.get());assertEquals(4,roleCalls.get());assertFalse(payload("real-market").has("sceneRejectedDrafts"));assertEquals(1,payload("real-market").path("scenePassages").size());assertTrue(payload("real-market").path("story").asText().contains("第二日天不亮就起身"));
 }
 @Test void establishedOffSceneRoleCanBeRequestedWithoutMovingOrRedrawingThenRemembersAuthoredMeeting()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处草棚',?,'乙在草棚。',4,4)",far,sid,json.writeValueAsString(List.of(b)));
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  var next=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String role=own.path("id").asText();
   assertTrue(own.toString().contains("仅"+role+"知道的私事"));assertFalse(own.toString().contains("仅"+(role.equals(a)?b:a)+"知道的私事"));
   if(role.equals(b)){assertEquals(next.get()?scene:far,own.path("recordedSceneId").asText());assertEquals(next.get(),own.path("ownRecords").toString().contains("重逢后说明旧绳结"));}
   roleCalls.incrementAndGet();return response(Map.of("intention","结合本人经历说明已知情况，不承诺未知的事"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();
   assertTrue(facts.path("characterIndex").toString().contains(b));
   if(step==1 || step==2){assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId(),"An author lookup must not move a person");
    if(step==1)assertFalse(facts.path("characterDossiers").has(b));
    else {assertEquals(2,facts.path("roleDirections").size());assertEquals(far,facts.path("characterDossiers").path(b).path("currentState").path("recordedSceneId").asText());assertEquals("AVAILABLE",facts.path("roleRequestResults").path(b).asText());}
    var plan=json.createObjectNode();plan.putArray("roleRequests").addObject().put("roleId",b);plan.withArray("roleRequests").addObject().put("roleId",b);return response(plan);
   }
   var d=passage(next.get()?"你们承接重逢时已说清的线索，问出了旧绳结的来源。":"乙来到石柱边与你们重逢，说明旧绳结的来历。","RESOLVED",120);d.putArray("sceneOutcomes");
   if(!next.get())d.withArray("sceneOutcomes").addObject().put("sceneId",scene).putArray("roleIds").add(b);
   var memories=d.putArray("roleExperiences");memories.addObject().put("roleId",b).put("experience",next.get()?"承接重逢时的线索，补充旧绳结的来源。":"重逢后说明旧绳结的来历，未得知甲的私事。").put("situation","在石柱边与玩家交谈，未作重大承诺。");return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("offscene","寻找守望者乙，普通问询自然处理。")).get("status"),payload("offscene").toString());assertEquals(2,roleCalls.get());assertEquals(3,mainCalls.get());
  assertEquals(scene,physical.actorScene(sid,b).orElseThrow().getSceneId());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
  assertEquals("远处草棚",jdbc.queryForObject("SELECT location_name FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER' AND JSON_CONTAINS(actor_ids,JSON_QUOTE(?)) ORDER BY entry_id LIMIT 1",String.class,sid,b));
  next.set(true);assertEquals("COMPLETED",operations.create(request("offscene-next","承接刚才的线索继续询问来源。")).get("status"));assertEquals(4,roleCalls.get());assertEquals(4,mainCalls.get());
  assertFalse(knowledge.privateContext(sid,b).contains("仅"+a+"知道的私事"));
 }
 @Test void offSceneIntentionsSurvivePartialRecoveryAndLookupDoesNotPublishRemoteEvents()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处草棚',?,'乙在草棚。',4,4)",far,sid,json.writeValueAsString(List.of(b)));
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  var repaired=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{mainCalls.incrementAndGet();if(mainCalls.get()==1){var plan=json.createObjectNode();plan.putArray("roleRequests").addObject().put("roleId",b);return response(plan);}
   var d=passage(repaired.get()?"你与甲查清了绳结来历。乙仍在草棚，尚未遇见你们。":"","RESOLVED",120);d.putArray("sceneOutcomes");d.putArray("roleExperiences").addObject().put("roleId",a).put("experience","与玩家确认绳结是修补所留。").put("situation","在石柱边，乙未参与。");return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("PARTIAL",operations.create(request("offscene-recover","与甲查清线索，必要时考虑乙的意愿。")).get("status"));assertEquals(2,roleCalls.get());assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId());
  repaired.set(true);assertEquals("COMPLETED",operations.recover(sid,pid,"offscene-recover",0).get("status"),payload("offscene-recover").toString());assertEquals(2,roleCalls.get(),"Accepted role responses must survive recovery");
  assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId());assertFalse(knowledge.privateContext(sid,b).contains("绳结是修补所留"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref LIKE 'story-experience:offscene-recover%'",Integer.class,sid,b));
 }
 @Test void recentOffSceneIntroductionIsAvailableOnAuthorRequestInsteadOfBeingPolledAutomatically()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var newcomer=new java.util.concurrent.atomic.AtomicReference<String>();String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'旧记录地点','[]','另一处地方',4,4)",far,sid);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));String who=own.path("id").asText();if(!List.of(a,b).contains(who))newcomer.set(who);roleCalls.incrementAndGet();return response(Map.of("intention","照本人所知回答旧事，未知之处不作保"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();
   if(step==1){var plan=json.createObjectNode();plan.putArray("newCharacters").addObject().put("name","旧铺掌柜").put("profile","认得旧绳结的掌柜，谨慎如实说话。");return response(plan);}
   if(step>2){if(!facts.path("roleDirections").has(newcomer.get())){var request=json.createObjectNode();request.putArray("roleRequests").addObject().put("roleId",newcomer.get());return response(request);}assertTrue(facts.path("characterDossiers").path(newcomer.get()).path("pastInformation").toString().contains("曾当面谈及旧绳结"));assertEquals(far,facts.path("characterDossiers").path(newcomer.get()).path("currentState").path("recordedSceneId").asText());}
   var d=passage(step==2?"掌柜当面讲了旧绳结的来历。":"承接掌柜已经说过的旧事，你们问清了新的线索。","RESOLVED",120);d.putArray("sceneOutcomes");d.putArray("roleExperiences");
   if(step==2)d.putArray("newCharacters").addObject().put("name","旧铺掌柜").put("profile","认得旧绳结的掌柜，谨慎如实说话。").put("experience","曾当面谈及旧绳结的来历。").put("situation","与玩家刚完成普通问询，未作承诺。");
   else d.withArray("roleExperiences").addObject().put("roleId",newcomer.get()).put("experience","曾当面谈及旧绳结，本次继续回答新的问询。").put("situation","保留本人所知，未知之处不作保。");return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("recent-cast","向铺主问清旧绳结。")).get("status"));assertNotNull(newcomer.get());
  // Large real dossiers exceed MySQL's default sort buffer. Reading recent cast must sort keys only.
  jdbc.update("UPDATE game_operation SET payload=JSON_SET(payload,'$.largeFrozenContext',?) WHERE session_id=? AND operation_id='recent-cast'","x".repeat(1024*1024),sid);
  // Test-only fixture reproduces the pre-fix introduction location bug. Production saves are never patched.
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a,b)),scene);jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(newcomer.get())),far);
  for(int n=1;n<=2;n++)assertEquals("COMPLETED",operations.create(request("recent-next-"+n,"承接刚才问出的线索继续询问。")).get("status"));
  assertEquals(9,roleCalls.get());assertEquals(6,mainCalls.get());assertEquals(far,physical.actorScene(sid,newcomer.get()).orElseThrow().getSceneId(),"Preparing context must not silently relocate someone");assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
 }
 @Test void authorCanRequestUnlocatedRoleWithoutLeakingPlayerSceneOrRelocatingThem()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));roleCalls.incrementAndGet();
   if(b.equals(own.path("id").asText())){
    assertEquals("",own.path("recordedSceneId").asText());assertTrue(own.path("locationMeaning").asText().contains("尚未登记"));
    assertTrue(own.path("establishedSelf").toString().contains("仅"+b+"知道的私事"));
    assertFalse(own.toString().contains("仅"+a+"知道的私事"));assertFalse(own.toString().contains("石柱上的绳结实际存在"));
    assertTrue(own.path("lastObservedPeople").isMissingNode());assertTrue(own.path("initialSceneHistory").isMissingNode());
   }
   return response(Map.of("intention","依据本人经历说明旧事，不替别人作保"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var facts=json.readTree(m.get(1).get("content"));
   if(mainCalls.incrementAndGet()==1){assertFalse(facts.path("roleDirections").has(b));var plan=json.createObjectNode();plan.putArray("roleRequests").addObject().put("roleId",b);return response(plan);}
   assertTrue(facts.path("roleDirections").has(b));assertTrue(physical.actorScene(sid,b).isEmpty());
   var d=passage("沈砚与甲整理已知的旧事，尚未和乙相遇。","RESOLVED",120);d.putArray("sceneOutcomes");return response(d);
  }).when(model).chatForJson(anyList());
  var done=operations.create(request("unlocated-request","整理线索，需要时考虑乙自己的意愿。"));assertEquals("COMPLETED",done.get("status"),payload("unlocated-request").toString());
  assertEquals(2,roleCalls.get());assertEquals(2,mainCalls.get());assertTrue(physical.actorScene(sid,b).isEmpty());
 }
 @Test void explicitTalkTargetWithoutSceneCanProvideOwnStoryContext()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE scene_id=?",scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));assertEquals(b,own.path("id").asText());assertEquals("",own.path("recordedSceneId").asText());roleCalls.incrementAndGet();return response(Map.of("intention","愿意回应本人知道的旧事"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{var d=passage("沈砚打算向乙询问旧事，后续相遇仍待故事展开。","RESOLVED",60);d.putArray("sceneOutcomes");return response(d);}).when(model).chatForJson(anyList());
  var r=request("unlocated-target","问问旧事");
  var target=new ConversationOperationService.Request(r.operationId(),sid,pid,scene,r.playerText(),new com.bs.narrative.dto.ActionRequest.DialogueInput(b,r.playerText()),r.branchEpoch(),r.expectedRevision(),true);
  assertEquals("COMPLETED",operations.create(target).get("status"));assertEquals(1,roleCalls.get());assertTrue(physical.actorScene(sid,b).isEmpty());
 }
 @Test void authorSelectedOffSceneCounterpartGetsOwnIntentionsBeforeFinalRelationshipScene()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'远处草棚',?,'乙在草棚',4,4)",far,sid,json.writeValueAsString(List.of(b)));jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));roleCalls.incrementAndGet();return response(Map.of("intention",own.path("id").asText().equals(b)?"不愿结盟，只愿说明已知路线":"愿意说明已经核实的线索"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId());
   if(mainCalls.incrementAndGet()==1){var d=passage("候选结盟尚未发生。","DECISION",100);d.putObject("decision").put("type","ALLIANCE").put("counterpartId",b).put("question","是否结盟？");return response(d);}
   assertTrue(f.path("characterDossiers").path(b).path("intentions").toString().contains("不愿结盟"));assertFalse(f.toString().contains("候选结盟尚未发生"));
   var d=passage("乙回来后，明确表示不愿结盟，只愿将已知路线说清。你听完说明，没有作新的重大承诺。","RESOLVED",100);d.putArray("sceneOutcomes").addObject().put("sceneId",scene).putArray("roleIds").add(b);d.putArray("roleExperiences").addObject().put("roleId",b).put("experience","返回石柱边，拒绝结盟，只说明路线。").put("situation","在石柱边，未结盟。");return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("auto-counterpart","询问乙愿意如何参与，重大关系等我表态。")).get("status"));assertEquals(2,roleCalls.get());assertEquals(2,mainCalls.get());assertFalse(payload("auto-counterpart").path("storyStop").path("requiresInput").asBoolean());assertFalse(payload("auto-counterpart").path("story").asText().contains("候选"));assertEquals(1,payload("auto-counterpart").path("scenePassages").size());
 }
 @Test void topLevelCastListHydratesKnownSpeakerEvenWithoutMovementOrExperienceInCandidate()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'桥东摊位',?,'乙在摊位',4,4)",far,sid,json.writeValueAsString(List.of(b)));jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),scene);
  doAnswer(call->{roleCalls.incrementAndGet();return response(Map.of("intention","愿意说明本人经手的旧铜情况"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));int n=mainCalls.incrementAndGet();
   if(n==1){assertFalse(f.path("roleDirections").has(b));var d=passage("候选：向桥东摊主询问旧铜。","RESOLVED",120);d.putArray("roleIds").add(b);d.putArray("sceneOutcomes");return response(d);}
   assertTrue(f.path("roleDirections").has(b));assertTrue(f.path("characterDossiers").path(b).path("intentions").toString().contains("本人经手"));assertFalse(f.toString().contains("候选：向桥东"));assertEquals(far,physical.actorScene(sid,b).orElseThrow().getSceneId());
   var d=passage("你走到桥东摊位，乙说明亲手经手过的旧铜，查到确切去向。","RESOLVED",120);d.putArray("roleIds").add(b);d.putArray("sceneOutcomes").addObject().put("sceneId",far).putArray("roleIds").add(player);d.putArray("roleExperiences").addObject().put("roleId",b).put("experience","在摊上向玩家说明旧铜去向。").put("situation","仍在桥东摊位营生，已经说明自己知道的旧铜去向。");return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("cast-list","去问桥东摊主旧铜去向。")).get("status"));assertEquals(2,mainCalls.get());assertEquals(2,roleCalls.get());assertEquals(1,payload("cast-list").path("scenePassages").size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' AND source_ref='story-experience:cast-list:0' AND content LIKE '%旧铜去向%'",Integer.class,sid,b));
 }
 @Test void establishedDiscoveriesSurviveLocationChangesFourChapterWindowAndAnIncompleteSummary()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_far";
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'另一处','[]','另一处地点',4,4)",far,sid);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));assertFalse(own.has("establishedStoryFacts"));assertFalse(own.toString().contains("BOOK_ONLY"));roleCalls.incrementAndGet();return response(Map.of("intention","只根据本人所知继续回应"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));int step=mainCalls.incrementAndGet();var d=passage(step==1?"你已经验看过寄包，知道包还留在老妇处。":"你沿着已经得到的线索继续调查，未取走寄包。","RESOLVED",100);d.putArray("sceneOutcomes");
   if(step==1)d.withArray("events").addObject().put("kind","FACT").put("objectName","寄包验看结果").put("after","BOOK_ONLY：包已经验看，仍留在老妇家；最终收件人尚未确认。");
   else {assertTrue(f.path("establishedStoryFacts").toString().contains("BOOK_ONLY"));assertTrue(f.path("establishedStoryFacts").toString().contains(scene));assertTrue(f.path("storyMemory").toString().contains("包在谁手未实"));}
   if(step==2)d.withArray("sceneOutcomes").addObject().put("sceneId",far).putArray("roleIds").add(player).add(a);
   d.put("continuity","不完整摘要：包在谁手未实。摘要不能删除原始验看记录。");return response(d);
  }).when(model).chatForJson(anyList());
  for(int n=1;n<=6;n++)assertEquals("COMPLETED",operations.create(request("fact-book-"+n,"继续调查下一条线索，不取走寄包。")).get("status"));
  assertEquals(far,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals(1,temporal.establishedStoryFacts(sid,player).size());assertTrue(temporal.establishedStoryFacts(sid,player).get(0).get("fact").toString().contains("仍留在老妇家"));
 }
 String legacyIntroductionFixture()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String far=scene+"_introplace";var id=new java.util.concurrent.atomic.AtomicReference<String>();
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'桥西草铺','[]','桥西草铺',4,4)",far,sid);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String who=json.readTree(m.get(1).get("content")).path("id").asText();if(!List.of(a,b).contains(who))id.set(who);roleCalls.incrementAndGet();return response(Map.of("intention","根据本人旧事回应，未知处不替人说定"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{int step=mainCalls.incrementAndGet();var d=step==1?json.createObjectNode():passage("你与甲到桥西草铺，掌柜说出他亲历的旧事。","RESOLVED",100);
   d.putArray("newCharacters").addObject().put("name","草铺掌柜").put("profile","桥西草铺掌柜，认得旧货郎，谨慎。").put("experience","在自己的草铺回答了来人的问询。").put("situation","留在桥西草铺，未承诺带路。");
   if(step>1){d.putArray("roleExperiences");d.putArray("sceneOutcomes").addObject().put("sceneId",far).putArray("roleIds").add(player).add(a);}return response(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("legacy-intro","到桥西询问掌柜的旧事。")).get("status"));
  // Test-only state reproduces the old serializer, not a production save edit.
  jdbc.update("UPDATE game_operation SET payload=JSON_SET(payload,'$.sceneDraft.newCharacters[0].sceneId',?) WHERE session_id=? AND operation_id='legacy-intro'",scene,sid);
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(b,id.get())),scene);jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(a)),far);
  jdbc.update("UPDATE role_knowledge SET scene_id=? WHERE session_id=? AND god_id=? AND source_ref IN (?,?,'story:situation')",scene,sid,id.get(),"genesis:"+id.get(),"story-introduction:legacy-intro:0");
  jdbc.update("DELETE FROM world_book WHERE session_id=? AND event_type='STORAGE_REPAIR'",sid);checkpoints.refreshCurrent(sid,pid);return id.get();
 }
 @Test void legacyIntroductionLocationRepairsFromPublishedResultOnceAndAgainAfterRewind()throws Exception{
  String newcomer=legacyIntroductionFixture(),far=scene+"_introplace";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var f=json.readTree(m.get(1).get("content"));assertEquals(far,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());assertTrue(f.path("characterDossiers").has(newcomer));return response(passage("掌柜承接此前已经说过的事，说明下一条线索。","RESOLVED",100));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("repair-current","承接掌柜旧事继续调查。")).get("status"));assertEquals(far,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='STORAGE_REPAIR'",Integer.class,sid));
  assertEquals(far,jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:situation'",String.class,sid,newcomer));
  checkpoints.restore(sid,1);checkpoints.resume(sid,pid);assertEquals(scene,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());
  assertEquals("COMPLETED",operations.create(request("repair-restored","继续承接掌柜的旧事。")).get("status"));assertEquals(far,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='STORAGE_REPAIR'",Integer.class,sid));
 }
 @Test void legacyLocationRepairKeepsALaterAuthoredReturnToTheOldPlace()throws Exception{
  String newcomer=legacyIntroductionFixture();
  doAnswer(call->{var d=passage("掌柜离开桥西草铺，回到了原来的石柱边。","RESOLVED",100);d.putArray("sceneOutcomes").addObject().put("sceneId",scene).putArray("roleIds").add(newcomer);return response(d);}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("later-move","让掌柜按本人的意愿回石柱边。")).get("status"));
  jdbc.update("DELETE FROM world_book WHERE session_id=? AND event_type='STORAGE_REPAIR'",sid);
  doAnswer(call->{assertEquals(scene,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());return response(passage("你与甲继续桥西的调查，掌柜仍在石柱边。","RESOLVED",100));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("keep-later-move","承接现在的分散，继续调查。")).get("status"));assertEquals(scene,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());
 }
 @Test void legacyLocationRepairCannotReplayAChapterAbsentFromTheCurrentTranscript()throws Exception{
  String newcomer=legacyIntroductionFixture();jdbc.update("DELETE FROM interaction_log WHERE session_id=?",sid);
  doAnswer(call->{assertEquals(scene,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());return response(passage("你与甲继续眼前的调查，没有人物突然从别处抵达。","RESOLVED",100));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("ignore-unpublished","继续调查眼前线索。")).get("status"));assertEquals(scene,physical.actorScene(sid,newcomer).orElseThrow().getSceneId());
 }
 @Test void actualCombatChangesWithDouAndMasteryThenPersistsOnce()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  jdbc.update("UPDATE god_profile SET base_zhi=0,base_hui=0,base_dou=50,base_wen=0,base_ben=0,base_gui=50 WHERE session_id=?",sid);
  jdbc.update("UPDATE god_profile SET base_ben=100 WHERE god_id=?",player);
  main(facts->{
   if(mainCalls.get()==1){var d=json.createObjectNode();d.putArray("mechanics").addObject().put("actorId",player).put("targetId",a).put("kind","ATTACK").put("statKey","DOU");return d;}
   var c=facts.at("/serverContests/0/facts");assertEquals(70,c.at("/comparison/actorActualDou").asInt());assertEquals(50,c.at("/comparison/targetActualDou").asInt());assertEquals(20,c.at("/comparison/difference").asInt());assertEquals(12,c.at("/comparison/threshold").asInt());assertEquals("ATTACKER_WIN",c.path("outcome").asText());assertTrue(c.path("physicalConsequence").asText().contains("不固定扣血"));
   return passage("你与甲交手后占了上风，甲受伤退开。","RESOLVED",60,action(player,"交手后甲退开。",0,30,List.of(Map.of("kind","CONTEST","targetId",a,"outcome",c.path("outcome").asText(),"fingerprint",c.path("fingerprint").asText()),Map.of("kind","HEALTH","actorId",a,"health",75))));
  });
  assertEquals("COMPLETED",operations.create(request("stat-mastery","与甲切磋一场。")).get("status"));assertEquals(100,new VitalStateService(jdbc).health(sid,player));assertEquals(75,new VitalStateService(jdbc).health(sid,a));
  assertEquals(52,actors.findById(player).orElseThrow().getBaseDou());assertEquals(48,actors.findById(a).orElseThrow().getBaseDou());
  operations.read(sid,pid,"stat-mastery",0L);assertEquals(75,new VitalStateService(jdbc).health(sid,a));assertEquals(2,mainCalls.get());
 }
 @Test void savedPlaceholderAbilitiesStayUnknownOnLaterTurnsAndCannotSettleAsFifteen()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  for(String marker:List.of("{\"statsMeaning\":\"旧版中性占位\"}","{\"sixStatsEstablished\":false}")){
   jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_type='SELF_PROFILE'",marker,sid,a);
   var c=conflicts.proposal(sid,scenes.findById(scene).orElseThrow(),player,player,json.createObjectNode().put("kind","ATTACK").put("targetId",a).put("statKey","DOU"),true);
   assertEquals("NARRATIVE",c.get("status"));assertFalse(c.containsKey("outcome"));
  }
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));if(a.equals(own.path("id").asText())){assertFalse(own.path("sixStatsEstablished").asBoolean(true));assertTrue(own.path("sixStats").isEmpty());}roleCalls.incrementAndGet();return response(Map.of("intention","依据自身经历解释旧记号"));}).when(model).chatSubForJson(anyList());
  main(facts->{assertFalse(facts.path("characterDossiers").path(a).at("/identity/sixStatsEstablished").asBoolean(true));assertTrue(facts.path("characterDossiers").path(a).at("/identity/sixStats").isEmpty());assertTrue(facts.path("currentWorld").path("resources").path(a).path("sixStats").isEmpty());return passage("甲依据自己的修补经历说明了刻痕。","RESOLVED",60);});
  assertEquals("COMPLETED",operations.create(request("placeholder-next","问清甲实际知道的旧记号。")).get("status"));assertEquals(100,new VitalStateService(jdbc).health(sid,a));
 }
 @Test void allSixActualValuesReachOwnIntentAndMainDossierWithoutSecondMastery()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);jdbc.update("UPDATE god_profile SET base_zhi=35,base_hui=26,base_dou=22,base_wen=30,base_ben=50,base_gui=20 WHERE god_id=?",a);
  var expected=json.valueToTree(Map.of("zhi",49,"hui",36,"dou",30,"wen",42,"ben",53,"gui",21));
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var own=json.readTree(messages.get(1).get("content"));if(a.equals(own.path("id").asText())){assertEquals(expected,own.path("sixStats"));assertTrue(own.path("sixStatsEstablished").asBoolean());}roleCalls.incrementAndGet();return response(Map.of("intention","结合已知经历核对刻痕"));}).when(model).chatSubForJson(anyList());
  main(facts->{assertEquals(expected,facts.path("characterDossiers").path(a).at("/identity/sixStats"));assertEquals(expected,facts.path("currentWorld").path("resources").path(a).path("sixStats"));return passage("甲核对了已有刻痕，说明其中可证实与仍需核实的部分。","RESOLVED",60);});
  assertEquals("COMPLETED",operations.create(request("six-stats-context","让甲根据实际所知核对刻痕。")).get("status"));
 }
 @Test void explicitRoleCombatIntentSuppliesFormulaBeforeFirstStoryWithoutExecutingIt()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);jdbc.update("UPDATE god_profile SET base_zhi=0,base_hui=0,base_dou=50,base_wen=0,base_ben=0,base_gui=50 WHERE session_id=?",sid);jdbc.update("UPDATE god_profile SET base_dou=10 WHERE god_id=?",player);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));var d=json.createObjectNode().put("intention","按约交手一次，不追击");if(a.equals(own.path("id").asText())){assertEquals(player,own.path("narrativeActorId").asText());d.putObject("contest").put("kind","ATTACK").put("targetId",player).put("statKey","DOU");}roleCalls.incrementAndGet();return response(d);}).when(model).chatSubForJson(anyList());
  main(facts->{assertEquals(1,mainCalls.get());assertEquals(100,new VitalStateService(jdbc).health(sid,player));var entry=facts.at("/serverContests/0");assertEquals(a,entry.path("actorId").asText());var c=entry.path("facts");assertEquals("ATTACKER_WIN",c.path("outcome").asText());assertEquals(40,c.at("/comparison/difference").asInt());var d=passage("甲与你按约交手，甲凭实际身手取胜，你受伤后双方停手。","RESOLVED",60);d.withArray("events").addObject().put("kind","CONTEST").put("targetId",player).put("outcome",c.path("outcome").asText()).put("fingerprint",c.path("fingerprint").asText());d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",75);return d;});
  assertEquals("COMPLETED",operations.create(request("role-contest","履行已经同意的那一场交手。")).get("status"));assertEquals(75,new VitalStateService(jdbc).health(sid,player));assertEquals(100,new VitalStateService(jdbc).health(sid,a));assertEquals(1,mainCalls.get());
 }
 @Test void roleCombatPlanDoesNotCauseDamageWhenAuthorWritesNoActualFight()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));var d=json.createObjectNode().put("intention","原本愿意切磋，也接受改日再来");if(a.equals(own.path("id").asText()))d.putObject("contest").put("kind","ATTACK").put("targetId",player).put("statKey","DOU");roleCalls.incrementAndGet();return response(d);}).when(model).chatSubForJson(anyList());
  main(facts->{assertEquals(1,facts.path("serverContests").size());var d=passage("你们决定先处理眼前的旧物问题，此次没有交手。","RESOLVED",60);d.putArray("contestResolutions").addObject().put("fingerprint",facts.at("/serverContests/0/facts/fingerprint").asText()).put("occurred",false);return d;});
  assertEquals("COMPLETED",operations.create(request("no-actual-contest","双方先谈清近况，是否交手按实际情形发展。")).get("status"));assertEquals(100,new VitalStateService(jdbc).health(sid,player));assertEquals(100,new VitalStateService(jdbc).health(sid,a));assertEquals(15,actors.findById(a).orElseThrow().getBaseDou());
 }
 @Test void authorOccurrenceFlagRecordsFormulaWithSeparatelyAuthoredInjury()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);jdbc.update("UPDATE god_profile SET base_zhi=0,base_hui=0,base_dou=50,base_wen=0,base_ben=0,base_gui=50 WHERE session_id=?",sid);jdbc.update("UPDATE god_profile SET base_dou=10 WHERE god_id=?",player);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));var d=json.createObjectNode().put("intention","按约交手一场");if(a.equals(own.path("id").asText()))d.putObject("contest").put("kind","ATTACK").put("targetId",player).put("statKey","DOU");roleCalls.incrementAndGet();return response(d);}).when(model).chatSubForJson(anyList());
  main(facts->{var d=passage("甲与你按约交手后获胜，你受了伤，双方收手。","RESOLVED",60);d.putArray("contestResolutions").addObject().put("fingerprint",facts.at("/serverContests/0/facts/fingerprint").asText()).put("occurred",true);d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",75);return d;});
  assertEquals("COMPLETED",operations.create(request("occurrence-only","完成约定的一场交手。")).get("status"));assertEquals(75,new VitalStateService(jdbc).health(sid,player));assertEquals(100,new VitalStateService(jdbc).health(sid,a));assertEquals(1,mainCalls.get());operations.read(sid,pid,"occurrence-only",0L);assertEquals(75,new VitalStateService(jdbc).health(sid,player));
 }
 void authoredCombatIntent(String disposition,String rescue)throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);jdbc.update("UPDATE god_profile SET base_zhi=0,base_hui=0,base_dou=50,base_wen=0,base_ben=0,base_gui=50 WHERE session_id=?",sid);jdbc.update("UPDATE god_profile SET base_dou=10 WHERE god_id=?",player);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var own=json.readTree(m.get(1).get("content"));var d=json.createObjectNode().put("intention",a.equals(own.path("id").asText())?disposition:rescue);var will=d.putObject("combatDisposition");
   if(a.equals(own.path("id").asText())){d.putObject("contest").put("kind","ATTACK").put("targetId",player).put("statKey","DOU");will.put("ifVictorious",disposition).put("restraint",disposition);}else will.put("rescueIntent",rescue);
   roleCalls.incrementAndGet();return response(d);
  }).when(model).chatSubForJson(anyList());
 }
 @Test void friendlyDefeatEndsWithoutDamageAndCarriesActualDispositionToAuthor()throws Exception{
  authoredCombatIntent("只作切磋，制住后立即松手，点拨招式，不伤人。","在旁观摩，不干预正常切磋。");
  main(facts->{assertTrue(facts.at(("/characterDossiers/"+a+"/intentions/combatDisposition/ifVictorious")).asText().contains("立即松手"));var c=facts.at("/serverContests/0/facts");assertFalse(c.path("health").has("actorAfter"));var d=passage("甲制住你的手腕后立即松开，指出你脚步的破绽。你认输后再次练习，这场切磋点到为止，双方没有受伤。","RESOLVED",180);d.putArray("contestResolutions").addObject().put("fingerprint",c.path("fingerprint").asText()).put("occurred",true);return d;});
  assertEquals("COMPLETED",operations.create(request("spar-no-injury","按约切磋，点到为止。")).get("status"));assertEquals(100,new VitalStateService(jdbc).health(sid,player));assertEquals(100,new VitalStateService(jdbc).health(sid,a));assertEquals(1,mainCalls.get());assertEquals(2,roleCalls.get());
 }
 @Test void unrescuedHostileDefeatCanEndInAuthoredDeathAndPersistsOnce()throws Exception{
  authoredCombatIntent("因前仇不愿放过，若制服且无人阻止，准备杀害败者。","此前与他有仇，不愿冒险救人。");
  main(facts->{assertTrue(facts.at("/characterDossiers/"+b+"/intentions/combatDisposition/rescueIntent").asText().contains("不愿"));var d=passage("甲击败你后没有停手，乙见状退开，没有施救。甲依照自己的报复意愿下了杀手，你伤重死去，这段旅程就此结束。","RESOLVED",120);d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",0);d.putArray("contestResolutions").addObject().put("fingerprint",facts.at("/serverContests/0/facts/fingerprint").asText()).put("occurred",true);return d;});
  var req=request("hostile-death","在无人保护时仍与甲敌对交手，接受实际后果。");assertEquals("COMPLETED",operations.create(req).get("status"));assertEquals(0,new VitalStateService(jdbc).health(sid,player));assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));operations.read(sid,pid,"hostile-death",0L);assertEquals(1,mainCalls.get());
 }
 @Test void hostileDefeatMayBeCaptureWithoutArtificialInjuryOrDeath()throws Exception{
  authoredCombatIntent("胜后留活口询问旧事，用绳索约束，不杀也不故意伤人。","不愿介入两人旧怨。");
  main(facts->{var d=passage("甲取得上风后收住攻势，将你制服捆住，留下活口准备询问旧事；乙没有出手。这次落败使你失去自由，尚未造成身体伤害。","RESOLVED",180);d.withArray("events").addObject().put("kind","BODY").put("actorId",player).putObject("after").put("bound",true);d.putArray("contestResolutions").addObject().put("fingerprint",facts.at("/serverContests/0/facts/fingerprint").asText()).put("occurred",true);return d;});
  assertEquals("COMPLETED",operations.create(request("hostile-capture","与甲交手，按真实意愿发展。")).get("status"));assertEquals(100,new VitalStateService(jdbc).health(sid,player));assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));assertTrue((Boolean)physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(scene).orElseThrow()).get("bound"));
 }
 @Test void willingRescueAndNonFixedInjuryAreAuthoredTogether()throws Exception{
  authoredCombatIntent("胜后准备追击，若有人实际阻拦再权衡风险。","曾受玩家相助，若亲眼见其遇险愿带他退走。");
  main(facts->{assertTrue(facts.at("/characterDossiers/"+b+"/intentions/combatDisposition/rescueIntent").asText().contains("带他退走"));var d=passage("甲击败你后仍想追击。乙想起先前受助之情，出面挡下追击，将受伤的你带到安全处。甲权衡后收手；你的伤尚需治疗，但已经脱离当下的死亡威胁。","RESOLVED",300);d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",61);d.putArray("contestResolutions").addObject().put("fingerprint",facts.at("/serverContests/0/facts/fingerprint").asText()).put("occurred",true);return d;});
  assertEquals("COMPLETED",operations.create(request("hostile-rescue","在乙在场时与甲交手，后果依各人实际意愿展开。")).get("status"));assertEquals(61,new VitalStateService(jdbc).health(sid,player));assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid));assertEquals(1,mainCalls.get());
 }
 @Test void authoredRewardRelationshipAndHealingStayConsistentAcrossTwoTurnsAndRewind()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var d=passage("林舟从祭台取得映潮印，确认它能开启潮门，与工匠甲约定同行。伤口重新包扎后已不再受束缚。","RESOLVED",300);
   d.withArray("events").addObject().put("kind","GAIN").put("actorId",player).put("name","映潮印").put("source","在祭台上实际取得");
   d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",73);
   d.withArray("events").addObject().put("kind","BODY").put("actorId",player).putObject("after").put("bound",false);
   var journal=d.putObject("playerJournal").put("playerName","林舟");journal.putArray("items").addObject().put("itemRef","映潮印").put("name","映潮印").put("kind","KEY_ITEM").put("knownEffects","开启已发现的潮门");journal.putArray("relationships").addObject().put("roleId",a).put("kind","ALLY").put("label","同行盟友").put("basis","调查之后双方实际同意同行");return d;});
  assertEquals("COMPLETED",operations.create(request("reward-stage","继续调查并取走已经发现的信物，接受先前约定的同行。")).get("status"));
  var first=panel.describe(sid,pid);assertEquals("林舟",first.get("name"));assertEquals(73,first.get("health"));assertTrue(first.get("importantItems").toString().contains("开启已发现的潮门"));assertEquals(1,((List<?>)first.get("friends")).size());
  String ref=items.owned(sid,actors.findById(player).orElseThrow()).stream().filter(i->i.name().equals("映潮印")).findFirst().orElseThrow().ref();
  main(facts->{var d=passage("林舟将映潮印赠给工匠甲，疗伤后恢复许多；双方说清分歧，解除了同行约定。","RESOLVED",400);
   d.withArray("events").addObject().put("kind","TRANSFER").put("fromId",player).put("toId",a).put("itemRef",ref).put("mode","GIFT");d.withArray("events").addObject().put("kind","HEALTH").put("actorId",player).put("health",88);
   var journal=d.putObject("playerJournal").put("playerName","错误姓名");journal.putArray("relationships").addObject().put("roleId",a).put("kind","ESTRANGED").put("label","已分道").put("basis","双方实际解除同行约定");return d;});
  assertEquals("COMPLETED",operations.create(request("gift-stage","赠出映潮印，治疗伤势，并与甲说清分歧后分道。")).get("status"));
  var second=panel.describe(sid,pid);assertEquals("林舟",second.get("name"));assertEquals(88,second.get("health"));assertTrue(((List<?>)second.get("importantItems")).isEmpty());assertTrue(((List<?>)second.get("friends")).isEmpty());
  checkpoints.restore(sid,1);var restored=panel.describe(sid,pid);assertEquals(73,restored.get("health"));assertTrue(restored.get("importantItems").toString().contains("映潮印"));assertEquals(1,((List<?>)restored.get("friends")).size());assertEquals(2,mainCalls.get());
 }
 @Test void explicitFinalCustodyAndAgreementResultsReachStorageAndTheFollowingChapter()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  var oldPact=json.createObjectNode().put("proposer",player).put("recipient",a).put("proposerName","林野").put("recipientName","工匠甲").put("status","ACTIVE").put("changedSeconds",0).put("terms","任何一方可明确告知解除");oldPact.putArray("events");
  for(String party:List.of(player,a))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'treaty:authored-test','既有盟约',?,'TREATY','',?,'',0)",sid,party,oldPact.toString(),scene);
  main(facts->{var d=passage("林野取走暗格中的映潮印。随后当面向甲解除旧盟约，甲听清并确认解除，双方仍平常相处。","RESOLVED",300);
   d.withArray("events").addObject().put("kind","FACT").put("objectName","映潮印").put("after","林野已实际取得并持有");
   d.putArray("itemOutcomes").addObject().put("name","映潮印").put("holderId",player).put("state","HELD").put("source","从已打开的祭台暗格实际取得");
   var journal=d.putObject("playerJournal").put("playerName","林野");journal.putArray("items").addObject().put("name","映潮印").put("kind","KEY_ITEM").put("knownEffects","与潮门印槽相合");journal.putArray("relationships").addObject().put("roleId",a).put("kind","ACQUAINTANCE").put("label","已解除旧盟的相识").put("basis","双方实际确认解除");journal.putArray("agreements").addObject().put("counterpartId",a).put("status","ENDED").put("basis","林野当面明确解除，甲听见并确认");return d;});
  assertEquals("COMPLETED",operations.create(request("final-outcomes","取走信物并与甲当面说清解除盟约。")).get("status"));
  assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("映潮印"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ENDED'",Integer.class,sid));assertTrue(knowledge.privateContext(sid,a).contains("已解除"));
  String ref=items.owned(sid,actors.findById(player).orElseThrow()).get(0).ref();
  main(facts->{assertTrue(facts.path("playerJournal").toString().contains("ENDED"));var d=passage("旧盟已经解除，不再重谈。林野把映潮印赠给甲，甲接过并保管，准备继续看潮门。","RESOLVED",200);d.putArray("itemOutcomes").addObject().put("itemRef",ref).put("name","映潮印").put("holderId",a).put("state","HELD").put("mode","GIFT");return d;});
  assertEquals("COMPLETED",operations.create(request("next-outcomes","承接解除结果，把实际持有的映潮印赠给甲。")).get("status"));assertTrue(((List<?>)panel.describe(sid,pid).get("importantItems")).isEmpty());assertTrue(items.describeOwned(sid,actors.findById(a).orElseThrow()).toString().contains("映潮印"));
  checkpoints.restore(sid,1);assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("映潮印"));assertTrue(knowledge.list(sid,player).toString().contains("ENDED"));
 }
 @Test void finalCustodySupersedesOnlyTheSameItemCompatibilityEffect()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(facts->{var d=passage("你从暗格取得映潮印，同时取得一张旧纸。","RESOLVED",120);d.withArray("events").addObject().put("kind","GAIN").put("name","映潮印").put("source","从暗格取得");d.withArray("events").addObject().put("kind","GAIN").put("name","旧纸").put("source","从石缝中取出");d.putArray("itemOutcomes").addObject().put("name","映潮印").put("holderId",player).put("state","HELD").put("source","从暗格取得");return d;});
  assertEquals("COMPLETED",operations.create(request("once-only-custody","取出暗格信物及石缝旧纸。")).get("status"));var held=items.describeOwned(sid,actors.findById(player).orElseThrow());assertEquals(1,held.stream().filter(i->"映潮印".equals(i.get("name"))).count());assertEquals(1,held.stream().filter(i->"旧纸".equals(i.get("name"))).count());
 }

 @Test void majorChoiceWordsAndActualConsequencesSurviveBeyondTheRecentChapterWindow()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String response="我只答应核验这一次，不替其余债务担保。";
  main(f->{int n=mainCalls.get();
   if(n==1){var d=passage("工匠甲邀请林野为全部旧债担保，等待他的答复。","DECISION",60);d.putObject("decision").put("type","GUARANTEE").put("counterpartId",a).put("question","是否为全部旧债担保？").put("stakes","须承担全部旧债");return d;}
   if(n==2){assertEquals(response,f.at("/decisionContext/playerResponse").asText());assertEquals("须承担全部旧债",f.at("/decisionContext/offeredChoice/stakes").asText());}
   if(n>2){assertFalse(f.has("decisionContext"));assertEquals(response,f.at("/continuityState/choiceResponses/0/context/playerResponse").asText());assertEquals("ACTIVE",f.at("/continuityState/consequences/0/status").asText());}
   var d=passage(n==2?"林野只同意核验一次，甲接受了这个范围，双方没有成立债务担保。":"两人按已说定的核验范围继续，不将旧债转给林野。","RESOLVED",60);
   if(n==2){var c=d.putObject("continuityState").putArray("consequences").addObject().put("key","limited-help").put("kind","GUARANTEE").put("playerChoice",response).put("actualOutcome","甲接受仅核验一次，不成立担保").put("status","ACTIVE");c.putArray("lastingEffects").add("仅核验一次，不承担其他旧债");}
   return d;
  });
  assertEquals("COMPLETED",operations.create(request("offer","听清甲的请求，重大担保等我答复。")).get("status"));
  assertEquals("COMPLETED",operations.create(request("answer",response)).get("status"));
  for(int n=0;n<5;n++)assertEquals("COMPLETED",operations.create(request("after-choice-"+n,"继续按已谈定范围处理。")).get("status"));
  var state=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:continuity-state'",String.class,sid,player));assertEquals(1,state.path("choiceResponses").size());assertEquals(1,state.at("/choiceResponses/0/publishedResults").size());
  assertFalse(knowledge.privateContext(sid,b).contains(response),"An uninvolved role must not receive the author's global choice ledger");
 }
 @Test void rewindRemovesConsequencesAndOldOperationReadsDoNotExposeTheAbandonedFuture()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);
  main(f->{int n=mainCalls.get();if(n==1){var d=passage("甲邀请林野结盟。","DECISION",60);d.putObject("decision").put("type","ALLIANCE").put("counterpartId",a).put("question","结盟吗？");return d;}
   if(n==3){assertFalse(f.path("continuityState").has("choiceResponses"));assertFalse(f.path("continuityState").has("consequences"));assertEquals("这次拒绝结盟。",f.at("/decisionContext/playerResponse").asText());}
   var d=passage(n==2?"已弃分支独有内容：林野同意结盟并取得密令。":"林野拒绝结盟，甲接受了拒绝。","RESOLVED",60);d.putObject("continuityState").putArray("consequences").addObject().put("key","alliance-choice").put("actualOutcome",n==2?"成立盟约":"拒绝盟约").put("status",n==2?"ACTIVE":"ENDED");return d;});
  operations.create(request("offer-branch","听清提议。"));operations.create(request("accept-branch","我答应结盟。"));checkpoints.restore(sid,1);
  var old=operations.read(sid,pid,"accept-branch",0L);assertEquals("STALE_BRANCH",old.get("status"));assertEquals(List.of(),old.get("lines"));assertFalse(old.containsKey("playerInput"));assertEquals(Map.of(),old.get("ruleSnapshot"));assertFalse(old.toString().contains("已弃分支独有内容"));
  var done=operations.create(new ConversationOperationService.Request("reject-branch",sid,pid,scene,"这次拒绝结盟。",null,1L,null,true));assertEquals("COMPLETED",done.get("status"),done.toString());
  var state=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:continuity-state'",String.class,sid,player));assertEquals(1,state.path("choiceResponses").size());assertFalse(state.toString().contains("我答应结盟"));assertEquals("ENDED",state.at("/consequences/0/status").asText());
 }
 @Test void consequenceSnapshotReplacesChangedArraysAndIsIdempotentOnReplay()throws Exception{
  var service=new StoryContinuityService(jdbc);var first=json.createObjectNode();var c=first.putArray("consequences").addObject().put("key","custody").put("status","ACTIVE");c.putArray("lastingEffects").add("代管信物");c.putArray("unresolved").add("待归还");
  service.apply(sid,player,scene,1,100,0,first,json.createObjectNode());var next=json.createObjectNode();var ended=next.putArray("consequences").addObject().put("key","custody").put("status","ENDED");ended.putArray("lastingEffects").add("信物已归还");ended.putArray("unresolved");
  service.apply(sid,player,scene,2,101,0,next,json.createObjectNode());var saved=service.read(sid,player);service.apply(sid,player,scene,2,101,0,next,json.createObjectNode());assertEquals(saved,service.read(sid,player));assertEquals(1,saved.path("consequences").size());assertTrue(saved.at("/consequences/0/unresolved").isEmpty());assertEquals("信物已归还",saved.at("/consequences/0/lastingEffects/0").asText());
 }

 @Test void revealedNameRetainsTheSameRoleAndHistoryAndRewindRestoresTheOldName()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);String oldName=actors.findById(a).orElseThrow().getGodName();
  main(f->{var d=passage("工匠甲告诉林野自己的名字叫周阿满，仍是刚才一起查看石柱的那个人。","RESOLVED",120);d.putArray("roleIds").add(a);d.putArray("newCharacters").addObject().put("existingRoleId",a).put("name","周阿满").put("basis","本人当面说出姓名");d.putArray("roleExperiences").addObject().put("roleId",a).put("experience","向林野报出姓名周阿满，保留修柱人的经历").put("situation","仍在原现场");return d;});
  var result=operations.create(request("reveal-name","问清甲如何称呼，仍接着原来的调查。"));assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals("周阿满",actors.findById(a).orElseThrow().getGodName());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='周阿满'",Integer.class,sid));assertEquals(2,roleCalls.get(),"Renaming must not request a second invented person's intentions");assertTrue(payload("reveal-name").at("/sceneDraft/newCharacters").isEmpty());assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story-experience:reveal-name:0'",String.class,sid,a).contains("修柱"));
  operations.create(request("reveal-name","问清甲如何称呼，仍接着原来的调查。"));assertEquals(2,roleCalls.get());checkpoints.restore(sid,0);assertEquals(oldName,actors.findById(a).orElseThrow().getGodName());
 }

 @Test void authorReceivesTrackedItemStateEvenWhenItsCurrentHolderIsOffScene()throws Exception{
  ReflectionTestUtils.setField(operations,"storyFirst",true);var tracked=items.owned(sid,actors.findById(a).orElseThrow()).get(0);var history=json.createObjectNode().put("ref",tracked.ref()).put("name","短刀").put("state","TRANSFERRED");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧持有残影',?,'ITEM_STATE','',?,'',0)",sid,player,tracked.ref(),history.toString(),scene);
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?",json.writeValueAsString(List.of(b)),scene);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var f=json.readTree(messages.get(1).get("content"));assertEquals(1,f.path("importantItemStates").size(),"Transferred historical holder rows must not masquerade as current custody");boolean found=false;for(var item:f.path("importantItemStates"))if(a.equals(item.path("holderId").asText()) && "短刀".equals(item.path("name").asText())){found=true;assertFalse(item.path("ref").asText().isBlank());}assertTrue(found,"Off-scene custody cannot disappear from the archive's evidence index");return response(passage("林野继续整理已发生的事，不产生新的交接。","RESOLVED",60));}).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("offscene-item-index","承接此前实际结果继续。")).get("status"));
 }
 @AfterEach void cleanup(){
  ReflectionTestUtils.setField(operations,"sceneStory",false);
  ReflectionTestUtils.setField(operations,"storyFirst",false);
  jdbc.update("DELETE FROM llm_operation_usage WHERE session_id=?",sid);
  for(String table:List.of("session_checkpoint_archive","game_goal_rule","game_operation","game_runtime","session_checkpoint","npc_decision_log","npc_memory","npc_goal","player_memory","player_objective","story_summary","interaction_log","world_book","god_relationship","scene_connection","session_npc_alias","npc_plan","world_clock","role_knowledge"))jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",sid);
  jdbc.update("DELETE FROM player_profile WHERE player_id=?",pid);jdbc.update("DELETE FROM game_session WHERE session_id=?",sid);jdbc.update("DELETE FROM game_scene WHERE session_id=?",sid);jdbc.update("DELETE FROM god_profile WHERE session_id=?",sid);
 }
}
