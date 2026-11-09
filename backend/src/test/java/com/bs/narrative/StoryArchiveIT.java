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

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","game.story-source-time-reading=false"})
class StoryArchiveIT {
 @Autowired ConversationOperationService operations;@Autowired SessionStateService checkpoints;
 @Autowired JdbcTemplate jdbc;@Autowired ItemStateService items;@Autowired GodProfileRepository actors;
 @Autowired StoryContinuityService continuity;
 @Autowired StoryArchiveService storyArchives;
 @Autowired GameActionService gameQueries;
 @Autowired GameSceneRepository scenes;@Autowired KnowledgeService knowledge;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();final AtomicInteger proseCalls=new AtomicInteger(),archiveCalls=new AtomicInteger(),roleCalls=new AtomicInteger();
 String sid,pid,self,near,far,scene,ref,failure="";
 final String story="陆衡在北亭确认解除旧约后沿南路离开，未再听见亭中谈话。林野与知秋留在亭中私下核对船号乙九。林野将映潮印赠给知秋，知秋接受保管，双方没有结盟。";
 LLMResponse reply(Object value)throws Exception{String raw=json.writeValueAsString(value);return new LLMResponse(raw,raw,0,0,0,0);}
 @BeforeEach void seed()throws Exception{
  for(String key:List.of("sceneStory","storyFirst","storyArchive"))ReflectionTestUtils.setField(operations,key,true);
  String suffix=UUID.randomUUID().toString().substring(0,8);sid="archive_"+suffix;pid="archive_u_"+suffix;self="archive_p_"+suffix;near="archive_n_"+suffix;far="archive_f_"+suffix;scene="archive_s_"+suffix;
  for(String role:List.of(self,near,far))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静',?)",role,sid,role.equals(self)?"林野":role.equals(near)?"知秋":"陆衡",role.equals(self)?"[\"映潮印\"]":"[]");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'北亭',?,'三人在北亭，林野持有映潮印。')",scene,sid,json.writeValueAsString(List.of(near,far)));
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,?,'unused',?,?)",pid,self,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",sid,pid,self,scene);
  for(String role:List.of(self,near,far))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'profile','背景','{}','SELF_PROFILE','',?,'',0)",sid,role,scene);
  var pact=json.createObjectNode().put("proposer",self).put("recipient",far).put("status","ACTIVE").put("changedSeconds",0).put("proposerName","林野").put("recipientName","陆衡");pact.putArray("events");
  for(String role:List.of(self,far))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'treaty:old','既有盟约',?,'TREATY','',?,'',0)",sid,role,pact.toString(),scene);
  ref=items.owned(sid,actors.findById(self).orElseThrow()).get(0).ref();checkpoints.initialize(sid,pid);
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());roleCalls.incrementAndGet();return reply(Map.of("intention","按本人实际经历自主行动","boundaries","不强迫别人结盟"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<Map<String,String>> input=call.getArgument(0);
   if(input.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var archiveIn=json.readTree(input.get(1).get("content"));assertEquals(story,archiveIn.path("finalStory").asText());assertFalse(archiveIn.toString().contains("候选错误记忆"));
    if("length".equals(failure))throw new LLMApiException("finish_reason=length",false).withPartialResponse("ARCHIVE_TRUNCATED_PRIVATE_PREFIX","length");
    if("provider".equals(failure))throw new LLMApiException("归档网络超时",false);
    if("schema".equals(failure))return reply(Map.of("story","归档不得冒充新的正文"));
    var projected=projection();if("storage".equals(failure))((ObjectNode)projected.path("itemOutcomes").get(0)).put("holderId","foreign_actor");return reply(projected);
   }
   proseCalls.incrementAndGet();var draft=json.createObjectNode().put("story",story).put("stop","RESOLVED").put("elapsedSeconds",300);draft.putArray("roleIds").add(near).add(far);draft.putArray("events");draft.putArray("sceneOutcomes");draft.putArray("newCharacters");
   draft.putArray("roleExperiences").addObject().put("roleId",far).put("experience","候选错误记忆：仍在场并听到乙九").put("situation","仍留在北亭");
   draft.putObject("playerJournal").putArray("agreements").addObject().put("counterpartId",near).put("status","ACTIVE").put("basis","候选错误记忆：帮忙即结盟");return reply(draft);
  }).when(model).chatForJson(anyList());
 }
 ObjectNode projection(){
  var result=json.createObjectNode().put("continuity","陆衡已解除旧约并离开；印赠知秋，二人仍未结盟。");result.put("story","伪造的归档正文，不得展示");result.putArray("events");result.putArray("contestResolutions");result.putArray("newCharacterExperiences");
  var where=result.putArray("sceneOutcomes").addObject();where.putArray("roleIds").add(far);where.putObject("scene").put("locationName","北亭外南路").put("opening","陆衡沿南路离开，之后去向未知。");
  result.putArray("itemOutcomes").addObject().put("itemRef",ref).put("name","映潮印").put("holderId",near).put("state","HELD").put("mode","GIFT");
  var experiences=result.putArray("roleExperiences");experiences.addObject().put("roleId",far).put("experience","确认解除旧约，随后沿南路离开。").put("situation","已离开北亭，不知道留在亭中的二人后来谈了什么。");experiences.addObject().put("roleId",near).put("experience","与林野私下核对船号乙九，接受保管映潮印，没有结盟。").put("situation","在北亭持有映潮印，仅为协助者。");
  var consequence=result.putObject("continuityState").putArray("consequences").addObject().put("key","ended-old-pact").put("kind","ALLIANCE").put("actualOutcome","陆衡与林野当面确认解除旧约").put("status","ENDED");consequence.putArray("lastingEffects").add("解除旧盟约，不再承担盟约义务");
  var journal=result.putObject("playerJournal");journal.putArray("items");journal.putArray("relationships").addObject().put("roleId",near).put("kind","ACQUAINTANCE").put("label","受托协助者").put("basis","实际接受保管，不等于结盟");journal.putArray("agreements").addObject().put("counterpartId",far).put("status","ENDED").put("basis","双方当面确认解除");return result;
 }
 ConversationOperationService.Request request(String id){return new ConversationOperationService.Request(id,sid,pid,scene,"解除旧约后继续私下核对并赠出映潮印。",null,0L,0L,true);}
 void assertCanonical()throws Exception{
  assertFalse(scenes.findById(scene).orElseThrow().getPresentGods().contains(far));assertTrue(items.owned(sid,actors.findById(near).orElseThrow()).stream().anyMatch(i->i.ref().equals(ref)));
  String farMemory=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_STORY_STATE'",String.class,sid,far);assertFalse(farMemory.contains("乙九"));assertTrue(farMemory.contains("已离开"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ACTIVE'",Integer.class,sid));assertTrue(knowledge.privateContext(sid,far).contains("已解除"));
  assertEquals("narrator："+story,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
 }
 @Test void archiveUsesFixedProseInsteadOfContradictoryDraftTables()throws Exception{assertEquals("COMPLETED",operations.create(request("canonical")).get("status"));assertCanonical();assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());}
 @Test void storyOnlyReplyStillArchivesAndCanRecoverMissingTimingWithoutRewriting()throws Exception{
  var timingReady=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("定稿归档阶段")){archiveCalls.incrementAndGet();var result=projection();if(timingReady.get())result.put("elapsedSeconds",300);return reply(result);}
   proseCalls.incrementAndGet();return reply(Map.of("story",story));
  }).when(model).chatForJson(anyList());
  assertEquals("PARTIAL",operations.create(request("only-story")).get("status"));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  timingReady.set(true);assertEquals("COMPLETED",operations.recover(sid,pid,"only-story",0).get("status"));assertCanonical();
  assertEquals(300L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(1,proseCalls.get());assertEquals(2,archiveCalls.get());assertEquals(2,roleCalls.get());
 }
 @Test void aPreviouslyUnparsedPaidReplyIsReusedAfterATransportParserRepair()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("定稿归档阶段")){archiveCalls.incrementAndGet();return reply(projection());}proseCalls.incrementAndGet();return new LLMResponse("{legacy broken reply","{legacy broken reply",0,0,0,0);}).when(model).chatForJson(anyList());
  var req=request("reparse-paid-original");assertEquals("PARTIAL",operations.create(req).get("status"));int roles=roleCalls.get();
  var saved=(ObjectNode)json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,req.operationId()));
  var prefix=json.createObjectNode().put("story",story).put("elapsedSeconds",300).toString();var tail=json.createObjectNode().put("stop","RESOLVED");tail.putArray("roleIds").add(near).add(far);
  saved.withObject("sceneDraft").put("unparsedResponse",prefix+","+tail.toString().substring(1));jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id=?",saved.toString(),sid,req.operationId());
  assertEquals("COMPLETED",operations.recover(sid,pid,req.operationId(),0).get("status"));assertCanonical();assertEquals(1,proseCalls.get(),"Saved author response must be parsed before requesting another paid draft");assertEquals(roles,roleCalls.get());assertEquals(1,archiveCalls.get());
 }
 @Test void latestLegacyStoryOnlyChapterCanBeArchivedWithoutDuplicatingPublishedProse()throws Exception{
  ReflectionTestUtils.setField(operations,"storyArchive",false);
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("定稿归档阶段")){archiveCalls.incrementAndGet();return reply(projection().put("elapsedSeconds",300));}proseCalls.incrementAndGet();return reply(Map.of("story",story));}).when(model).chatForJson(anyList());
  var req=request("legacy-only-story");assertEquals("COMPLETED",operations.create(req).get("status"));assertEquals(0,archiveCalls.get());
  ReflectionTestUtils.setField(operations,"storyArchive",true);assertEquals("COMPLETED",operations.recover(sid,pid,req.operationId(),0).get("status"));assertCanonical();
  var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,req.operationId()));
  assertEquals(1,payload.path("scenePassages").size());assertTrue(payload.path("publishedArchiveRepaired").asBoolean());assertEquals(300,payload.path("endSeconds").asLong());
  operations.create(req);operations.recover(sid,pid,req.operationId(),0);assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertEquals(2,roleCalls.get());
 }
 @Test void importantItemCanReferenceTheSameNewCoarseSceneByNameBeforeItsIdExists()throws Exception{
  String text="林野带映潮印来到南路石亭，将印留在石桌上。知秋与陆衡仍在北亭。";
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("定稿归档阶段")){
    var a=json.createObjectNode();var cast=a.putArray("endingCast");cast.addObject().put("roleId",self).putObject("scene").put("locationName","南路石亭").put("opening","林野在石亭，映潮印已留在石桌。");cast.addObject().put("roleId",near).put("sceneId",scene);cast.addObject().put("roleId",far).put("sceneId",scene);
    var item=a.putArray("itemOutcomes").addObject().put("itemRef",ref).put("name","映潮印").put("state","IN_SCENE").put("sceneId",scene);item.putObject("scene").put("locationName","南路石亭");a.putArray("events");return reply(a);
   }
   var d=json.createObjectNode().put("story",text).put("stop","RESOLVED").put("elapsedSeconds",300);d.putArray("roleIds").add(near).add(far);return reply(d);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("new-item-scene")).get("status"));String actual=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid);assertNotEquals(scene,actual);
  var record=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND source_ref=?",String.class,sid,ref));assertEquals("IN_SCENE",record.path("state").asText());assertEquals(actual,record.path("sceneId").asText());assertFalse(record.path("holderId").asText().equals(self));
 }
 void resumeArchiveFailure(String kind)throws Exception{
  failure=kind;assertEquals("PARTIAL",operations.create(request("resume")).get("status"));assertTrue(items.owned(sid,actors.findById(self).orElseThrow()).stream().anyMatch(i->i.ref().equals(ref)));assertTrue(scenes.findById(scene).orElseThrow().getPresentGods().contains(far));
  failure="";assertEquals("COMPLETED",operations.recover(sid,pid,"resume",0).get("status"));assertCanonical();assertEquals(1,proseCalls.get(),"recovery reuses the exact stored prose");assertEquals(2,roleCalls.get(),"recovery does not request new intentions");assertEquals(2,archiveCalls.get());operations.read(sid,pid,"resume",0L);assertEquals(2,archiveCalls.get());
 }
 @Test void providerFailureResumesOnlyArchivingWithoutRewritingProse()throws Exception{resumeArchiveFailure("provider");}
 @Test void malformedStoryReplyIsSavedOnceAndResumedWithoutRedrawingSuccessfulRoles()throws Exception{
  var answers=new AtomicInteger();reset(model);
  doAnswer(call->{roleCalls.incrementAndGet();return reply(Map.of("intention","按本人实际经历自主行动"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);if(messages.get(0).get("content").contains("定稿归档阶段"))return reply(projection());
   if(answers.incrementAndGet()==1)return new LLMResponse("{unparseable published candidate",null,0,0,0,0);
   var draft=json.createObjectNode().put("story",story).put("stop","RESOLVED").put("elapsedSeconds",300);draft.putArray("roleIds").add(near).add(far);return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("PARTIAL",operations.create(request("story-format")).get("status"));assertEquals(1,answers.get());assertEquals(2,roleCalls.get());
  var stored=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='story-format'",String.class,sid));assertEquals("{unparseable published candidate",stored.at("/sceneDraft/unparsedResponse").asText());assertTrue(items.owned(sid,actors.findById(self).orElseThrow()).stream().anyMatch(i->i.ref().equals(ref)));
  assertEquals("COMPLETED",operations.recover(sid,pid,"story-format",0).get("status"));assertEquals(2,answers.get());assertEquals(2,roleCalls.get());assertCanonical();
 }
 @Test void malformedProjectionAlsoRetainsTheExactDraftForRecovery()throws Exception{
  failure="schema";assertEquals("PARTIAL",operations.create(request("archive-raw")).get("status"));
  var saved=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='archive-raw'",String.class,sid));
  assertTrue(saved.path("sceneArchiveRawResponse").asText().contains("归档不得冒充新的正文"));assertFalse(saved.path("sceneArchiveRawValidated").asBoolean());
  failure="";assertEquals("COMPLETED",operations.recover(sid,pid,"archive-raw",0).get("status"));assertCanonical();assertEquals(1,proseCalls.get());assertEquals(2,archiveCalls.get());
 }
 @Test void noContestOrNewCharacterDoesNotRequireRedundantEmptyChangeArrays()throws Exception{
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var out=projection();out.remove(java.util.List.of("contestResolutions","newCharacterExperiences"));return reply(out);
   }
   proseCalls.incrementAndGet();var draft=json.createObjectNode().put("story",story).put("stop","RESOLVED").put("elapsedSeconds",300);draft.putArray("roleIds").add(near).add(far);return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("optional-arrays")).get("status"));assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertCanonical();
 }
 @Test void aStoredPaidArchiveCanBeReusedAfterASerializerFixWithoutAnyNewModelCall()throws Exception{
  var current=(StoryArchiveService)ReflectionTestUtils.getField(operations,"storyArchives");
  var oldSerializer=spy(current);doThrow(new IllegalArgumentException("模拟旧版序列化器无法读取此结构")).when(oldSerializer).normalize(anyString());
  try{
   ReflectionTestUtils.setField(operations,"storyArchives",oldSerializer);
   assertEquals("PARTIAL",operations.create(request("reuse-paid-archive")).get("status"));
   var saved=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='reuse-paid-archive'",String.class,sid));
   assertTrue(saved.path("sceneArchiveRawResponse").asText().contains("映潮印"));assertFalse(saved.path("sceneArchiveRawValidated").asBoolean());
   ReflectionTestUtils.setField(operations,"storyArchives",current);
   assertEquals("COMPLETED",operations.recover(sid,pid,"reuse-paid-archive",0).get("status"));assertCanonical();
   assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertEquals(2,roleCalls.get());
  }finally{ReflectionTestUtils.setField(operations,"storyArchives",current);}
 }
 @Test void storageFailureRollsBackMovesAndItemsThenResumesOnlyTheArchive()throws Exception{resumeArchiveFailure("storage");}
 @Test void aPaidMalformedRoleReplyIsRetainedAndRecoveryOnlyRequestsTheMissingRole()throws Exception{
  var calls=new AtomicInteger();doAnswer(call->{int n=calls.incrementAndGet();return n==1?new LLMResponse("{broken role template",null,0,0,0,0):reply(Map.of("intention","按本人实际经历自主行动"));}).when(model).chatSubForJson(anyList());
  assertEquals("PARTIAL",operations.create(request("role-format")).get("status"));
  var stored=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='role-format'",String.class,sid));
  assertTrue(stored.path("sceneDirectionAttempts").toString().contains("{broken role template"));assertEquals(2,calls.get());assertEquals(0,proseCalls.get());
  assertEquals("COMPLETED",operations.recover(sid,pid,"role-format",0).get("status"));assertEquals(3,calls.get());assertCanonical();
 }
 @Test void finalDestinationIncludesVisitorsAndGuideWhileTheDepartedPersonStaysOnHisOwnRoute()throws Exception{
  String travel="陆衡与林野、知秋离开北亭，到岔口后独自沿南路远去，后续去向不明。老周带林野、知秋穿过塘边土路，抵达徐伯院中。徐伯当面出示旧渡道抄录，三人一起看清乙九的记号，老周留在门边。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    assertEquals(travel,input.path("finalStory").asText());assertTrue(StoryArchiveService.CONTRACT.contains("endingCast"));
    var result=json.createObjectNode().put("continuity","林野与知秋、老周在徐伯院中查清记号；陆衡已独自离去。");for(String field:List.of("events","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))result.putArray(field);result.putObject("playerJournal");
    var places=result.putArray("sceneOutcomes");var departed=places.addObject();departed.putArray("roleIds").add(far);departed.putObject("scene").put("locationName","北亭岔口外南路").put("opening","陆衡自此离去，后续去向不明。");
    var destination=places.addObject();var together=destination.putArray("roleIds").add(self).add(near);
    for(var role:input.at("/before/roles"))if(Set.of("老周","徐伯").contains(role.path("name").asText()))together.add(role.path("roleId").asText());
    assertEquals(4,together.size());destination.putObject("scene").put("locationName","榆平东头徐伯院").put("opening","四人查看乙九的旧渡道抄录。");return reply(result);
   }
   var draft=json.createObjectNode().put("story",travel).put("stop","RESOLVED").put("elapsedSeconds",1200);draft.putArray("roleIds").add(near).add(far);var fresh=draft.putArray("newCharacters");for(String name:List.of("老周","徐伯"))fresh.addObject().put("name",name).put("profile",name+"熟悉本地码头，如实提供自己所知。").put("narrativeTier","EXTRA");return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("final-place")).get("status"));String destination=jdbc.queryForObject("SELECT current_scene_id FROM game_session WHERE session_id=?",String.class,sid);assertEquals("榆平东头徐伯院",scenes.findById(destination).orElseThrow().getLocationName());
  var present=scenes.findById(destination).orElseThrow().getPresentGods();assertTrue(present.contains(near));assertFalse(present.contains(far));
  for(String name:List.of("老周","徐伯")){String role=jdbc.queryForObject("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,name);assertTrue(present.contains(role));}
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND location_name='北亭岔口外南路' AND present_gods LIKE ?",Integer.class,sid,"%"+far+"%"));
 }
 @RepeatedTest(3) void namedPlaceRosterKeepsANewShopkeeperAtHisShopAcrossSaveRereadAndRewind()throws Exception{
  String prose="林野和知秋到丁掌柜的铺子里看过旧账。丁掌柜留在铺内，二人告别后走在回北亭的路上，尚未抵达。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();assertEquals("NAMED_PLACES_V1",input.path("finalPlaceFormat").asText());
    var result=json.createObjectNode();var places=result.putObject("endingPlaces");places.putObject("return").put("locationName","北亭回程路").put("opening","林野与知秋正在回程，尚未到家。");places.putObject("shop").put("locationName","丁家铺子").put("opening","丁掌柜留在店中。");
    var cast=result.putArray("endingCast");cast.addObject().put("roleId",self).put("placeKey","return").put("sceneId",scene);cast.addObject().put("roleId",near).put("placeKey","return").put("sceneId",scene);
    String merchant="";for(var person:input.at("/before/roles"))if("丁掌柜".equals(person.path("name").asText()))merchant=person.path("roleId").asText();assertFalse(merchant.isBlank());
    cast.addObject().put("roleId",merchant).put("placeKey","shop").put("sceneId",scene);return reply(result);
   }
   proseCalls.incrementAndGet();var draft=json.createObjectNode().put("story",prose).put("stop","RESOLVED").put("elapsedSeconds",3600);draft.putArray("roleIds").add(near);
   draft.putArray("newCharacters").addObject().put("name","丁掌柜").put("profile","市集独立经营铺子的掌柜，愿给来客看本人保留的旧账。").put("narrativeTier","EXTRA");return reply(draft);
  }).when(model).chatForJson(anyList());
  var req=request("named-visitor");assertEquals("COMPLETED",operations.create(req).get("status"));
  String merchant=jdbc.queryForObject("SELECT god_id FROM god_profile WHERE session_id=? AND god_name='丁掌柜'",String.class,sid);
  String current=jdbc.queryForObject("SELECT current_scene_id FROM game_session WHERE session_id=?",String.class,sid);assertEquals("北亭回程路",scenes.findById(current).orElseThrow().getLocationName());
  assertTrue(scenes.findById(current).orElseThrow().getPresentGods().contains(near));assertFalse(scenes.findById(current).orElseThrow().getPresentGods().contains(merchant));
  String shop=jdbc.queryForObject("SELECT scene_id FROM game_scene WHERE session_id=? AND location_name='丁家铺子'",String.class,sid);assertTrue(scenes.findById(shop).orElseThrow().getPresentGods().contains(merchant));
  assertFalse(scenes.findById(scene).orElseThrow().getPresentGods().contains(merchant));
  var displayed=json.valueToTree(gameQueries.describeScene(pid,sid));assertEquals(current,displayed.path("sceneId").asText());
  assertTrue(displayed.path("presentGods").toString().contains(near));assertFalse(displayed.path("presentGods").toString().contains(merchant),"The actual player-facing scene must not show the off-scene merchant");
  assertEquals(displayed.path("presentGods"),json.valueToTree(gameQueries.describeScene(pid,sid)).path("presentGods"));
  int authors=proseCalls.get(),roles=roleCalls.get();operations.create(req);operations.recover(sid,pid,req.operationId(),0);
  assertEquals(authors,proseCalls.get());assertEquals(roles,roleCalls.get());assertEquals(1,archiveCalls.get());assertEquals(3600L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  checkpoints.restore(sid,0);assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM game_session WHERE session_id=?",String.class,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='丁掌柜'",Integer.class,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND location_name IN ('北亭回程路','丁家铺子')",Integer.class,sid));
 }
 @Test void anOmittedNewPersonReturnsToAuthorForIdentityAndPersonalIntentions()throws Exception{
  var authors=new AtomicInteger();var projections=new AtomicInteger();String prose="林野与知秋遇见老周，老周指明已知渡道，三人留在北亭交谈。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    projections.incrementAndGet();var out=json.createObjectNode().put("continuity","三人在北亭交谈，老周提供自己已知的路线。");for(String field:List.of("events","sceneOutcomes","itemOutcomes","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");
    // Deliberately use the exact name in an ID field: initially it is unknown, later uniquely indexed.
    out.putArray("roleExperiences").addObject().put("roleId","老周").put("experience","在北亭告诉二人自己知道的渡道。").put("situation","在北亭与二人交谈。");return reply(out);
   }
   int n=authors.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));if(n>1){assertFalse(input.has("episodePlan"),"A discarded cast proposal must not be published progress");assertTrue(input.path("alreadyHappenedThisChapter").isEmpty());}
   var draft=json.createObjectNode().put("story",prose).put("stop","RESOLVED").put("elapsedSeconds",60).put("authorPlan","先处理眼前渡道问题；老周可拒绝，不预定结果。");draft.putArray("roleIds").add(near);
   if(n>1)draft.putArray("newCharacters").addObject().put("name","老周").put("profile","北亭附近的渡工，愿意告诉过路人自己熟悉的渡道。").put("narrativeTier","EXTRA");return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("missing-cast")).get("status"));assertEquals(3,authors.get());assertEquals(2,projections.get());assertEquals(3,roleCalls.get(),"Only the newly declared person's intentions are added");
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='老周'",Integer.class,sid));assertEquals("narrator："+prose,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
 }
 @Test void discardedCandidateCannotReplaceCommittedProgressWhileAddingCast()throws Exception{
  var authors=new AtomicInteger();var archives=new AtomicInteger();
  String first="林野与知秋已经抵达闸口，午后稳住水势。",last="守闸人说明先前未报的缘由，众人安排看守，林野确认家人已经安顿。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    int n=archives.incrementAndGet();var out=json.createObjectNode().put("continuity",n==1?first:last);
    for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");
    var memory=out.putObject("continuityState").putObject("narrativeState");memory.putArray("completed").add(n==1?"已抵闸并稳水":"已确认家人安顿");memory.put("currentSituation",n==1?"午后在闸口":"傍晚留人守闸");memory.putArray("open");return reply(out);
   }
   int n=authors.incrementAndGet();if(n>1){
    assertEquals(first,input.at("/alreadyHappenedThisChapter/0").asText());
    assertEquals("已抵闸并稳水",input.at("/committedPassageResults/0/completed/0").asText());
    assertEquals("先处理眼前水情",input.at("/episodePlan/content/centralQuestion").asText());
    var guide=json.readTree(messages.get(messages.size()-1).get("content"));assertEquals("CONTINUING_AFTER_COMMITTED",guide.at("/episodeFocus/intentStatus").asText());
    assertFalse(input.toString().contains("GHOST_ALREADY_SAILED_AGAIN"));
   }
   var draft=json.createObjectNode().put("elapsedSeconds",60);draft.putArray("roleIds").add(near);
   if(n==1){draft.put("story",first).put("stop","CONTINUE");draft.putObject("authorPlan").put("centralQuestion","先处理眼前水情");}
   else{
    draft.put("story",n==2?"候选：三人又乘船抵达闸口。":last).put("stop","RESOLVED");
    draft.putArray("newCharacters").addObject().put("name","守闸人").put("profile","在闸边当值，按自己的亲历说明水情。").put("narrativeTier","EXTRA");
    if(n==2)draft.putObject("authorPlan").put("currentProgress","GHOST_ALREADY_SAILED_AGAIN");
   }
   return reply(draft);
  }).when(model).chatForJson(anyList());
  var result=operations.create(request("committed-plan"));assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(3,authors.get());assertEquals(2,archives.get());
  var p=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='committed-plan'",String.class,sid));
  assertEquals(first+"\n\n"+last,p.path("story").asText());assertEquals(2,p.path("scenePassageResults").size());assertFalse(p.has("sceneAuthorPlan"));assertFalse(p.has("committedAuthorPlan"));
 }
 @Test void cumulativeArchiveRetainsEarlyResolvedFactsAfterSixChapters()throws Exception{
  String anchor="EARLY_RESOLVED_FACT:旧委托已经完成，不能再作为未解问题；信物由玩家持有。";
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'story:continuity','累计摘要',?,'STORY_MEMORY','',?,'',0)",sid,self,anchor,scene);
  var steps=new AtomicInteger();doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    assertTrue(input.at("/before/storyMemory/0").asText().contains(anchor));assertFalse(input.toString().contains("候选错误记忆"));
    var out=json.createObjectNode().put("continuity",anchor+" 新经历已经推进到第"+steps.get()+"段。");for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");return reply(out);
   }
   assertTrue(input.at("/storyMemory/0").asText().contains(anchor));if(steps.get()>=5)assertTrue(input.at("/openingPublishedChapter/story").asText().contains("这是第1段新的实际经过"));int step=steps.incrementAndGet();var draft=json.createObjectNode().put("story","这是第"+step+"段新的实际经过，不重演已完成的旧委托。").put("stop","RESOLVED").put("elapsedSeconds",60);draft.putArray("roleIds").add(near);return reply(draft);
  }).when(model).chatForJson(anyList());
  for(int i=0;i<6;i++){long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("keep-"+i,sid,pid,scene,"继续当前事件",null,0L,revision,true)).get("status"));}
  assertEquals(6,steps.get());assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:continuity'",String.class,sid,self).contains(anchor));
 }
 @Test void narrativeHandoffIsAReplaceableSnapshotAndRewindsWithPublishedConsequences(){
  var first=json.createObjectNode();var handoff=first.putObject("narrativeState").put("currentSituation","已经交货，准备休息");handoff.putArray("completed").add("药已交付，工钱已收");handoff.putArray("open").add("后生尚未报姓名");
  continuity.apply(sid,self,scene,0,101,0,first,json.createObjectNode());checkpoints.refreshCurrent(sid,pid);
  var second=json.createObjectNode();var next=second.putObject("narrativeState").put("currentSituation","后生已经说明姓名，约定天亮返乡");next.putArray("completed").add("药已交付，工钱已收").add("后生报姓名");next.putArray("open");
  continuity.apply(sid,self,scene,1,102,0,second,json.createObjectNode());
  assertEquals(0,continuity.read(sid,self).at("/narrativeState/open").size());assertEquals(2,continuity.read(sid,self).at("/narrativeState/completed").size());
  assertEquals(102,continuity.read(sid,self).at("/narrativeState/source/logId").asLong());
  checkpoints.restore(sid,0);assertEquals("后生尚未报姓名",continuity.read(sid,self).at("/narrativeState/open/0").asText());verifyNoInteractions(model);
 }
 @Test void oneAuthorClockSurvivesWrongArchiveRecoveryNextChapterAndRewind()throws Exception{
  var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();if(fail.get())throw new LLMApiException("temporary archive interruption",false);
    var result=projection();result.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",0.25).put("unit","DAY");return reply(result);
   }
   int n=proseCalls.incrementAndGet();var draft=json.createObjectNode().put("story",story+" 这段生活已经过去。").put("stop","RESOLVED").put("elapsedSeconds",n==1?691200:86400);draft.putArray("roleIds").add(near).add(far);
   draft.putObject("timeProgress").put("scope","THIS_PASSAGE").putArray("elapsedParts").addObject().put("amount",n==1?7:1).put("unit","DAY");return reply(draft);
  }).when(model).chatForJson(anyList());
  var first=request("one-clock");assertEquals("PARTIAL",operations.create(first).get("status"));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  int retainedRoles=roleCalls.get();fail.set(false);assertEquals("COMPLETED",operations.recover(sid,pid,"one-clock",0).get("status"));
  assertEquals(1,proseCalls.get());assertEquals(retainedRoles,roleCalls.get());assertEquals(604800L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='one-clock'",String.class,sid));assertEquals(604800,payload.at("/sceneArchiveResult/timeProgress/elapsedSeconds").asLong());assertEquals(21600,payload.at("/sceneArchiveResult/archiveTimeInterpretation/elapsedSeconds").asLong());
  operations.create(first);operations.recover(sid,pid,"one-clock",0);assertEquals(1,proseCalls.get());assertEquals(2,archiveCalls.get());
  long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);
  assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("one-clock-next",sid,pid,scene,"下一天自然接续",null,0L,revision,true)).get("status"));assertEquals(691200L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  checkpoints.restore(sid,1);assertEquals(604800L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  checkpoints.restore(sid,0);assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
 }
 @Test void reviewedTimeAndImportantAccountsPersistIntoTheNextChapterAndRewindTogether()throws Exception{
  var step=new AtomicInteger();doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));
   if(m.get(0).get("content").contains("接着整理定稿故事的重要账目来源")){var out=json.createObjectNode().put("continuity","既有旧账解释仍有方向矛盾。");var account=out.putObject("continuityState").putArray("accounts").addObject().put("key","shortfall").put("conclusion","六两收入与三两支出方向不同，不是已支九两");account.putArray("unresolved").add("旧总额解释仍有冲突");out.putArray("knownPlotReviews");return reply(out);}
   if(m.get(0).get("content").contains("定稿归档阶段")){
    var out=json.createObjectNode();out.putArray("endingCast");
    if(step.get()==1){out.putObject("timeProgress").put("from","第十五日傍晚").put("to","第三十一日傍晚").put("elapsedSeconds",1382400).put("basis","新增十六日");var account=out.putObject("continuityState").putArray("accounts").addObject().put("key","shortfall").put("conclusion","六两收入与三两支出方向不同，不是已支九两");account.putArray("unresolved").add("旧总额解释仍有冲突");}
    else assertEquals("shortfall",input.at("/before/continuityState/accounts/0/key").asText());
    return reply(out);
   }
   if(step.get()>0)assertEquals("六两收入与三两支出方向不同，不是已支九两",input.at("/continuityState/accounts/0/conclusion").asText());
   int n=step.incrementAndGet();var authored=json.createObjectNode().put("story",n==1?"从第十五日傍晚接续，到满月领薪；翌日探友到晚。":"翌日整理既有记载，矛盾仍有来源可查。").put("stop","RESOLVED").put("elapsedSeconds",n==1?2678400:86400);
   authored.putObject("timeProgress").put("scope","THIS_PASSAGE").putArray("elapsedParts").addObject().put("amount",n==1?16:1).put("unit","DAY");return reply(authored);
  }).when(model).chatForJson(anyList());
  var first=request("time-account-first");assertEquals("COMPLETED",operations.create(first).get("status"));assertEquals(1382400L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("time-account-next",sid,pid,scene,"承接已知事实继续",null,0L,revision,true)).get("status"));
  assertEquals(1468800L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(1,continuity.read(sid,self).path("accounts").size());
  operations.create(first);assertEquals(2,step.get());checkpoints.restore(sid,1);assertEquals(1382400L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals(1,continuity.read(sid,self).path("accounts").size());
  checkpoints.restore(sid,0);assertTrue(continuity.read(sid,self).isEmpty());
 }
 @Test void plotIdsLookupStructuredPlanningAndRewindFollowThePublishedStory()throws Exception{
  var authors=new AtomicInteger();var archives=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    int step=archives.incrementAndGet();assertEquals("UNCOMMITTED_AUTHOR_PLAN",input.at("/authorPlanning/status").asText());
    var out=json.createObjectNode().put("continuity",step==1?"已经发现旧册缺页。":"已查明缺页缘由。");for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");
    var updates=out.putObject("plotUpdates");var event=updates.putArray("events").addObject().put("title","旧册缺页的缘由").put("category","INVESTIGATION").put("status",step==1?"OPEN":"RESOLVED").put("development",input.path("finalStory").asText());event.putObject("playerView").put("title","旧册缺页").put("summary",input.path("finalStory").asText()).put("status",step==1?"OPEN":"RESOLVED");
    var thread=updates.putArray("foreshadows").addObject().put("title","缺页留下的撕痕").put("status",step==1?"OPEN":"PAID_OFF");
    if(step==1){event.put("key","oldBook");thread.put("eventId","oldBook").put("setup","旧册缺了一页").put("question","为何撕走？");}
    else{String eventId=input.at("/before/plotMemory/eventIndex/0/eventId").asText(),threadId=input.at("/before/plotMemory/recentEvents/0/foreshadows/0/threadId").asText();assertFalse(eventId.isBlank());assertFalse(threadId.isBlank());event.put("eventId",eventId);thread.put("threadId",threadId).put("payoff","知秋拿出残页核对撕痕，承认当年撕页是为保全受牵连的人。");}
    return reply(out);
   }
   int step=authors.incrementAndGet();var draft=json.createObjectNode();draft.putObject("authorPlan").put("centralQuestion","缺页的缘由与人物选择").put("currentProgress",step==1?"尚未写本段":"已有缺页事实，不预定对方供述");
   if(step==2){String eventId=input.at("/plotMemory/eventIndex/0/eventId").asText();assertFalse(eventId.isBlank());draft.putArray("plotRequests").addObject().put("eventId",eventId).put("includePassage",true);return reply(draft);}
   if(step==3){assertFalse(input.has("episodePlan"),"The lookup-only candidate has not been published");assertTrue(input.at("/plotResults/0/publishedPassage").asText().contains("旧册中间缺了一页"));assertFalse(input.at("/plotResults/0/records/0/event/eventId").asText().isBlank());}
   draft.put("story",step==1?"林野与知秋翻查旧册，发现旧册中间缺了一页，撕痕留在装订处。":"知秋从旧信中取出残页，与装订处的撕痕吻合。她承认当年撕走它是为保全受牵连的人。林野听明缘由，将已查清的来龙去脉记下，两人结束这次核对。").put("stop","RESOLVED").put("elapsedSeconds",120);draft.putArray("roleIds").add(near);return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("plot-first")).get("status"));String eventId=jdbc.queryForObject("SELECT event_id FROM story_plot_event WHERE session_id=?",String.class,sid);
  assertEquals("COMPLETED",operations.create(request("plot-first")).get("status"));assertEquals(1,authors.get());
  long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);
  assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("plot-second",sid,pid,scene,"承接前文核对缺页缘由，普通问答自然完成。",null,0L,revision,true)).get("status"));
  assertEquals(3,authors.get());assertEquals(2,archives.get());assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM story_plot_event WHERE session_id=? AND event_id=?",String.class,sid,eventId));assertEquals("PAID_OFF",jdbc.queryForObject("SELECT status FROM story_foreshadow WHERE session_id=?",String.class,sid));
  checkpoints.restore(sid,1);assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM story_foreshadow WHERE session_id=?",String.class,sid));assertEquals(eventId,jdbc.queryForObject("SELECT event_id FROM story_plot_event WHERE session_id=?",String.class,sid));
  // A pre-migration checkpoint has no new table keys and must still erase future plot records.
  jdbc.update("UPDATE session_checkpoint SET payload=JSON_REMOVE(payload,'$.story_plot_event','$.story_foreshadow') WHERE session_id=? AND round_no=0",sid);
  checkpoints.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event WHERE session_id=?",Integer.class,sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_foreshadow WHERE session_id=?",Integer.class,sid));
 }
 @Test void aNewMajorChoiceKeepsItsActualStakesWithoutAcceptingItOrPublishingTheWriterPlan()throws Exception{
  String stakes="签下担保会让林野对今后可能的赔款承担连带责任。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    var out=json.createObjectNode().put("continuity","旧册问题已说明，新的担保仍未接受。");for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");return reply(out);
   }
   var input=json.readTree(messages.get(1).get("content"));assertTrue(input.has("plotSourcePages"));
   var draft=json.createObjectNode().put("story","知秋说明旧册缘由，并自行补写说明。随后她请林野作担保，说明可能的赔款责任，等待回答。").put("stop","DECISION").put("elapsedSeconds",300);draft.putArray("roleIds").add(near);draft.putObject("decision").put("type","COMMITMENT").put("question","是否担保？").put("stakes",stakes).put("counterpartId",near);draft.putObject("authorPlan").putObject("playerBoundary").put("reserved","PRIVATE_RESERVED_DECISION");return reply(draft);
  }).when(model).chatForJson(anyList());
  var result=operations.create(request("stakes-boundary"));assertEquals("COMPLETED",result.get("status"));var visible=json.valueToTree(result);assertEquals(stakes,visible.at("/ruleSnapshot/storyProgress/stakes").asText());assertTrue(visible.at("/ruleSnapshot/storyProgress/requiresInput").asBoolean());assertFalse(visible.toString().contains("PRIVATE_RESERVED_DECISION"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND source_god_id=?",Integer.class,sid,near));
 }
 ConversationOperationService.Request currentRequest(String id){return new ConversationOperationService.Request(id,sid,pid,scene,"按已经发生的经历继续。",null,0L,jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid),true);}
 @Test void repeatedCancelledEmptyAttemptsDoNotReplaceTheLastPublishedChapters()throws Exception{
  assertEquals("COMPLETED",operations.create(request("published-before-errors")).get("status"));
  long published=jdbc.queryForObject("SELECT log_id FROM interaction_log WHERE session_id=?",Long.class,sid);
  doThrow(new LLMApiException("测试中断，无实际模型调用",false)).when(model).chatSubForJson(anyList());
  for(int i=0;i<5;i++){
   String id="empty-attempt-"+i;
   assertEquals("PARTIAL",operations.create(currentRequest(id)).get("status"));
   assertEquals("CANCELLED",operations.cancel(sid,pid,id,0).get("status"));
  }
  doAnswer(call->{roleCalls.incrementAndGet();return reply(Map.of("intention","按本人现有经历继续"));}).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(currentRequest("after-errors")).get("status"));
  var saved=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='after-errors'",String.class,sid));
  var previous=saved.at("/sceneFrameFacts/previousChapters");
  assertEquals(1,previous.size(),"empty attempts do not consume the four published chapter slots");
  assertEquals(published,previous.get(0).path("logId").asLong());
  assertEquals("narrator："+story,previous.get(0).path("story").asText());
  assertFalse(saved.at("/sceneFrameFacts").has("openingPublishedChapter"),"the already recent opening is not sent twice");
  assertEquals(7,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid),"failed attempts remain recorded, not deleted");
 }
 @Test void emptyAttemptsBeforeAnyStoryCannotBecomeThePublishedOpening()throws Exception{
  doThrow(new LLMApiException("测试中断，无实际模型调用",false)).when(model).chatSubForJson(anyList());
  assertEquals("PARTIAL",operations.create(request("no-story")).get("status"));
  operations.cancel(sid,pid,"no-story",0);
  doAnswer(call->reply(Map.of("intention","按本人现有经历继续"))).when(model).chatSubForJson(anyList());
  assertEquals("COMPLETED",operations.create(currentRequest("first-story")).get("status"));
  var saved=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='first-story'",String.class,sid));
  assertTrue(saved.at("/sceneFrameFacts/previousChapters").isEmpty());
  assertFalse(saved.at("/sceneFrameFacts").has("openingPublishedChapter"));
 }
 @Test void longPublishedProseIsRetrievedWholeAfterOrderingOnlyChapterKeys()throws Exception{
  assertEquals("COMPLETED",operations.create(request("before-long-prose")).get("status"));
  String longStory="narrator："+"已发表的较长故事段落。".repeat(40000)+"完整结尾";
  jdbc.update("UPDATE interaction_log SET processed_dialog=? WHERE session_id=?",longStory,sid);
  assertEquals("COMPLETED",operations.create(currentRequest("after-long-prose")).get("status"));
  var saved=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='after-long-prose'",String.class,sid));
  assertEquals(longStory,saved.at("/sceneFrameFacts/previousChapters/0/story").asText(),"long chapters are neither sorted as payloads nor truncated");
 }
 @Test void anAuthoredFinalLocationIsSavedWithoutRequiringADuplicateSceneGroup()throws Exception{
  String fixed="林野向知秋道别，独自前往西闸查看闸体，随后走在沿河回程的路上。知秋留在北亭，没有获知西闸所见。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    var out=json.createObjectNode().put("continuity",fixed);for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))out.putArray(field);out.putObject("playerJournal");
    var cast=out.putArray("endingCast");cast.addObject().put("roleId",self).put("location","西闸回程河堤").put("basis","随后走在沿河回程的路上。");cast.addObject().put("roleId",near).put("sceneId",scene);
    out.withArray("roleExperiences").addObject().put("roleId",self).put("experience","独自到西闸查看，正沿河回程。").put("situation","独自走在西闸回程河堤。");
    out.withArray("roleExperiences").addObject().put("roleId",near).put("experience","看见林野告别离开。").put("situation","留在北亭，不知林野之后的所见。");return reply(out);
   }
   var draft=json.createObjectNode().put("story",fixed).put("stop","RESOLVED").put("elapsedSeconds",7200);draft.putArray("roleIds").add(near);return reply(draft);
  }).when(model).chatForJson(anyList());
  var result=json.valueToTree(operations.create(request("ending-cast-location")));assertEquals("COMPLETED",result.path("status").asText());
  assertEquals("西闸回程河堤",result.at("/scene/locationName").asText());assertTrue(result.at("/scene/presentGods").isEmpty());
  assertNotEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
  assertTrue(scenes.findById(scene).orElseThrow().getPresentGods().contains(near));
  assertFalse(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:situation'",String.class,sid,near).contains("查看闸体"));
 }
 @Test void historicalPlotReferencesDoNotRequestNewIntentionsOrDiscardTheFixedStory()throws Exception{
  String past="past_"+UUID.randomUUID().toString().substring(0,8);
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion) VALUES(?,?,'旧证人','MORTAL','平静')",past,sid);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var out=projection();var event=out.putObject("plotUpdates").putArray("events").addObject().put("title","旧证词关联").put("category","INVESTIGATION").put("status","RESOLVED").put("development","旧证人的历史资料与本段记载关联，不表示本人在场。");event.putArray("roleIds").add(self).add(past);return reply(out);
   }
   proseCalls.incrementAndGet();var draft=json.createObjectNode().put("story",story).put("stop","RESOLVED").put("elapsedSeconds",300);draft.putArray("roleIds").add(near).add(far);return reply(draft);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("historical-reference")).get("status"));
  assertEquals(2,roleCalls.get());assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertCanonical();
 }
 @Test void aFrozenArchivedCandidateDiscardedByOldHydrationCanFinishWithoutMoreCalls()throws Exception{
  failure="provider";assertEquals("PARTIAL",operations.create(request("old-hydration")).get("status"));
  var payload=(ObjectNode)json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='old-hydration'",String.class,sid));
  payload.put("sceneArchiveSource",StoryArchiveService.source(payload.path("sceneDraft")));payload.set("sceneArchiveResult",projection());payload.remove("sceneDraft");
  payload.withObject("sceneRoleInputs").putArray("unused_historical_reference");
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id='old-hydration'",payload.toString(),sid);
  failure="";assertEquals("COMPLETED",operations.recover(sid,pid,"old-hydration",0).get("status"));assertCanonical();
  assertEquals(2,roleCalls.get());assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());
 }

 @Test void fixedProseArchivePersistsConsequencesAndCachedRecoveryDoesNotRepeatCalls()throws Exception{
  failure="storage";var result=operations.create(request("consequence-recovery"));assertEquals("PARTIAL",result.get("status"));int paid=archiveCalls.get();failure="";
  var rows=jdbc.queryForList("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='consequence-recovery'",sid);var payload=(ObjectNode)json.readTree(rows.get(0).get("payload").toString());
  // Repair only the known broken binding in the saved response, preserving its other results.
  var projection=projection();payload.set("sceneArchiveResult",projection);payload.put("sceneArchiveRawResponse",projection.toString());payload.put("sceneArchiveSource",StoryArchiveService.source(payload.path("sceneDraft")));
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id='consequence-recovery'",payload.toString(),sid);
  result=operations.recover(sid,pid,"consequence-recovery",0);assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(paid,archiveCalls.get());assertEquals(1,proseCalls.get());
  var state=new StoryContinuityService(jdbc).read(sid,self);assertEquals("ENDED",state.at("/consequences/0/status").asText());assertTrue(state.at("/consequences/0/source/logId").asLong()>0);assertCanonical();
 }

 @Test void deliveryToAnUncardedShopClerkStoresTheShopRatherThanTheRemoteEmployer()throws Exception{
  doAnswer(call->{List<Map<String,String>> m=call.getArgument(0);
   if(m.get(0).get("content").contains("定稿归档阶段")){var projection=json.createObjectNode();projection.putArray("endingCast").addObject().put("roleId",self).putObject("scene").put("locationName","青溪药铺").put("opening","林野已交货，药匣由掌柜代店铺收存。");projection.putArray("itemOutcomes").addObject().put("itemRef",ref).put("name","映潮印").put("state","IN_SCENE").put("source","掌柜代表店铺收存，原委托人在北亭").putObject("scene").put("locationName","青溪药铺");return reply(projection);}
   return reply(json.createObjectNode().put("story","林野把映潮印交给青溪药铺的临时掌柜，由店铺代为保管。原委托人陆衡仍在北亭，没有接回信物。").put("stop","RESOLVED").put("elapsedSeconds",600));
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("shop-custody")).get("status"));var state=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND source_ref=?",String.class,sid,ref));assertEquals("IN_SCENE",state.path("state").asText());assertEquals("",state.path("holderId").asText());assertEquals(jdbc.queryForObject("SELECT scene_id FROM game_scene WHERE session_id=? AND location_name='青溪药铺'",String.class,sid),state.path("sceneId").asText());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='临时掌柜'",Integer.class,sid));assertTrue(items.owned(sid,actors.findById(far).orElseThrow()).isEmpty());
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void institutionCanHoldAnImportantObjectWithoutMovingAnyCharacterThere()throws Exception{
  var out=json.createArrayNode();var item=out.addObject().put("itemRef",ref).put("name","映潮印").put("state","IN_SCENE").put("source","由仁和药铺的临时掌柜收存");item.putObject("scene").put("locationName","尚无人建卡的仁和药铺");
  new StoryItemOutcomes(jdbc,items,actors).apply(sid,out);var saved=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND source_ref=?",String.class,sid,ref));assertEquals("IN_SCENE",saved.path("state").asText());assertEquals(scene,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));assertEquals("[]",jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,saved.path("sceneId").asText()));assertTrue(items.owned(sid,actors.findById(self).orElseThrow()).isEmpty());
 }

 @Test void truncatedArchiveRetainsItsPaidFragmentAndRecoversOnlyTheArchive()throws Exception{
  failure="length";var result=operations.create(request("truncated-archive"));assertEquals("PARTIAL",result.get("status"));assertEquals("MODEL_OUTPUT_TRUNCATED",result.get("pendingKind"));assertFalse(result.toString().contains("ARCHIVE_TRUNCATED_PRIVATE_PREFIX"));
  var paused=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='truncated-archive'",String.class,sid));assertEquals("ARCHIVE_TRUNCATED_PRIVATE_PREFIX",paused.at("/modelFailureReplies/0/rawResponse").asText());assertEquals("ARCHIVE",paused.at("/modelFailureReplies/0/phase").asText());
  failure="";assertEquals("COMPLETED",operations.recover(sid,pid,"truncated-archive",0).get("status"));assertEquals(1,proseCalls.get());assertEquals(2,roleCalls.get());assertEquals(2,archiveCalls.get());assertCanonical();
 }
 @Test void unpublishedCopyeditRecoversOnceAndFeedsPublishedTextToPersonalMemoryAndNextChapter()throws Exception{
  String coda=" 往后几日又到亭中重领了一次报酬。";
  var interrupted=new java.util.concurrent.atomic.AtomicBoolean(true);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals("UNPUBLISHED_COPYEDIT",input.path("publicationMode").asText());
    assertEquals(story+coda,input.path("finalStory").asText());assertTrue(input.at("/chapterOutcome/roleIds").toString().contains(near));
    if(interrupted.get())throw new LLMApiException("archive interrupted before publication",false);
    var result=projection();result.putObject("publication").putArray("edits").addObject().put("before",coda).put("after","").put("reason","删除本段以外的重复领取尾声");return reply(result);
   }
   proseCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));
   if(proseCalls.get()>1){assertTrue(input.path("previousChapters").toString().contains(story));assertFalse(input.path("previousChapters").toString().contains(coda));}
   var d=json.createObjectNode().put("story",story+coda).put("stop","RESOLVED").put("elapsedSeconds",300);d.putArray("roleIds").add(near).add(far);return reply(d);
  }).when(model).chatForJson(anyList());
  var first=request("copyedit");assertEquals("PARTIAL",operations.create(first).get("status"));int roles=roleCalls.get();interrupted.set(false);
  assertEquals("COMPLETED",operations.recover(sid,pid,"copyedit",0).get("status"));assertCanonical();assertEquals(roles,roleCalls.get());assertEquals(1,proseCalls.get());
  var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='copyedit'",String.class,sid));
  assertEquals(story+coda,payload.at("/sceneDraft/story").asText());assertEquals(story,payload.path("story").asText());
  assertEquals(story,json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref LIKE 'story-experience:%'",String.class,sid,self)).path("text").asText());
  operations.create(first);operations.recover(sid,pid,"copyedit",0);assertEquals(2,archiveCalls.get());assertEquals(1,proseCalls.get());
  long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);
  assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("copyedit-next",sid,pid,scene,"继续生活",null,0L,revision,true)).get("status"));
  checkpoints.restore(sid,1);assertEquals("narrator："+story,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));assertEquals(300L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
 }
 @Test void finalManuscriptOwnsTextClockAndNextChapterAcrossRecoveryAndRewind()throws Exception{
  String finalText=story+" 此后三日林野照常在亭旁营生。",rough=finalText+" 早先的问答又从头说了一遍。";
  var interrupted=new java.util.concurrent.atomic.AtomicBoolean(true);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();if(interrupted.get())throw new LLMApiException("temporary publication interruption",false);
    var result=projection();var publication=result.putObject("publication");
    if(proseCalls.get()==1){publication.put("story",finalText);publication.putObject("timeProgress").put("to","三日营生之后").putArray("elapsedParts").addObject().put("amount",3).put("unit","DAY");}
    else{publication.put("unchanged",true);publication.putObject("timeProgress").put("to","又过一日").putArray("elapsedParts").addObject().put("amount",1).put("unit","DAY");result.putArray("itemOutcomes");result.putArray("sceneOutcomes");}
    return reply(result);
   }
   proseCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));
   if(proseCalls.get()>1){assertTrue(input.path("previousChapters").toString().contains(finalText));assertFalse(input.path("previousChapters").toString().contains("早先的问答又从头"));assertEquals(259200,input.path("storySeconds").asLong());}
   var d=json.createObjectNode().put("story",proseCalls.get()==1?rough:"林野接着做完一日营生，回亭收拾。").put("stop","RESOLVED");d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",proseCalls.get()==1?7:1).put("unit","DAY");
   var ids=d.putArray("roleIds").add(near);if(proseCalls.get()==1)ids.add(far);return reply(d);
  }).when(model).chatForJson(anyList());
  var first=request("final-manuscript");assertEquals("PARTIAL",operations.create(first).get("status"));int retainedRoles=roleCalls.get();interrupted.set(false);
  assertEquals("COMPLETED",operations.recover(sid,pid,"final-manuscript",0).get("status"));assertEquals(1,proseCalls.get());assertEquals(retainedRoles,roleCalls.get());
  assertEquals("narrator："+finalText,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));assertEquals(259200L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  assertEquals(finalText,json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref LIKE 'story-experience:%'",String.class,sid,self)).path("text").asText());
  var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='final-manuscript'",String.class,sid));assertEquals(7,payload.at("/sceneDraft/timeProgress/elapsedParts/0/amount").asInt());assertEquals("FINAL_PUBLICATION",payload.at("/sceneArchiveResult/timeProgress/version").asText());
  operations.create(first);operations.recover(sid,pid,"final-manuscript",0);assertEquals(2,archiveCalls.get());assertEquals(1,proseCalls.get());
  long revision=jdbc.queryForObject("SELECT revision FROM game_runtime WHERE session_id=?",Long.class,sid);
  assertEquals("COMPLETED",operations.create(new ConversationOperationService.Request("final-manuscript-next",sid,pid,scene,"再做一日营生",null,0L,revision,true)).get("status"));
  assertEquals(345600L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  checkpoints.restore(sid,1);assertEquals(259200L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertEquals("narrator："+finalText,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
 }
 @Test void malformedFinalUnitsRecoverOnlyTheSavedPublicationWithoutRepeatingSuccessfulAuthorAndRoles()throws Exception{
  var malformed=new java.util.concurrent.atomic.AtomicBoolean(true);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);
   if(messages.get(0).get("content").contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var result=projection();var publication=result.putObject("publication").put("story",story);
    var part=publication.putObject("timeProgress").putArray("elapsedParts").addObject().put("unit","MINUTE");if(malformed.get())part.put("amount","unreadable-final-units");else part.put("amount",5);return reply(result);
   }
   proseCalls.incrementAndGet();var d=json.createObjectNode().put("story",story+" 此处误加了一段尚未发生的尾声。").put("stop","RESOLVED").put("elapsedSeconds",300);d.putArray("roleIds").add(near).add(far);return reply(d);
  }).when(model).chatForJson(anyList());
  var first=request("final-format");assertEquals("PARTIAL",operations.create(first).get("status"));assertEquals(0L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  var paused=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='final-format'",String.class,sid));assertTrue(paused.path("sceneArchiveRawResponse").asText().contains("unreadable-final-units"));
  int retainedRoles=roleCalls.get();malformed.set(false);assertEquals("COMPLETED",operations.recover(sid,pid,"final-format",0).get("status"));assertEquals(1,proseCalls.get());assertEquals(retainedRoles,roleCalls.get());assertEquals(2,archiveCalls.get());assertCanonical();
 }
   @Test void focusedSourceClockResumesWithoutRepeatingTheAuthorArchiveOrRoles()throws Exception{
    String longText=story+" 此后第五日商定，次日出行，再过一日办完家事，又过一日落雨，次日雨歇收船。";
    var timeCalls=new AtomicInteger();ReflectionTestUtils.setField(operations,"sourceTimeReading",true);
    try{
     doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
      if(prompt.contains("正文定稿阶段")){if(timeCalls.incrementAndGet()==1)throw new LLMApiException("small source-time read interrupted",false);var input=json.readTree(messages.get(1).get("content"));assertEquals(longText,input.path("story").asText());assertFalse(input.has("chapterOutcome"));var r=json.createObjectNode();r.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",9).put("unit","DAY");return reply(r);}
      if(prompt.contains("定稿归档阶段")){archiveCalls.incrementAndGet();var r=projection();r.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");return reply(r);}
      proseCalls.incrementAndGet();var d=json.createObjectNode().put("story",longText).put("stop","RESOLVED");d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",5).put("unit","DAY");d.putArray("roleIds").add(near).add(far);return reply(d);
     }).when(model).chatForJson(anyList());
     var request=request("source-clock");assertEquals("PARTIAL",operations.create(request).get("status"));int roles=roleCalls.get();
     assertEquals("COMPLETED",operations.recover(sid,pid,"source-clock",0).get("status"));assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertEquals(roles,roleCalls.get());assertEquals(2,timeCalls.get());assertEquals(777600L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
     var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='source-clock'",String.class,sid));assertEquals("DONE",payload.at("/sceneArchiveResult/timeReading/status").asText());assertEquals(longText,payload.path("story").asText());operations.create(request);operations.recover(sid,pid,"source-clock",0);assertEquals(2,timeCalls.get());
    }finally{ReflectionTestUtils.setField(operations,"sourceTimeReading",false);}
   }
   @Test void resultArchivingOnlySeesTheFinalManuscriptAndCannotRewriteItOrItsClock()throws Exception{
    String finalText=story+" 此后九日照常营生，雨歇当晚收船。",rough=story+" 多余的旧问答又从头开始。";var finals=new AtomicInteger();ReflectionTestUtils.setField(operations,"sourceTimeReading",true);
    try{
     doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
      if(prompt.contains("正文定稿阶段")){finals.incrementAndGet();var r=json.createObjectNode();r.putObject("publication").put("story",finalText);var milestone=r.putObject("timeProgress").putArray("milestones").addObject();milestone.putArray("elapsedPartsFromStart").addObject().put("amount",9).put("unit","DAY");return reply(r);}
      if(prompt.contains("定稿归档阶段")){archiveCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals(finalText,input.path("finalStory").asText());assertEquals("FINAL_MANUSCRIPT_RECORD_ONLY",input.path("publicationMode").asText());assertEquals(777600,input.at("/chapterOutcome/elapsedSeconds").asLong());var r=projection();r.putObject("publication").put("story","归档不得再改成这篇文字").putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",1).put("unit","SECOND");return reply(r);}
      proseCalls.incrementAndGet();var d=json.createObjectNode().put("story",rough).put("stop","RESOLVED");d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");d.putArray("roleIds").add(near).add(far);return reply(d);
     }).when(model).chatForJson(anyList());
     var request=request("fixed-final-manuscript");assertEquals("COMPLETED",operations.create(request).get("status"));assertEquals(1,proseCalls.get());assertEquals(1,archiveCalls.get());assertEquals(1,finals.get());
     var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id='fixed-final-manuscript'",String.class,sid));assertEquals(rough,payload.at("/sceneDraft/story").asText());assertEquals(finalText,payload.path("story").asText());assertEquals(777600,payload.at("/sceneArchiveResult/timeProgress/elapsedSeconds").asLong());assertEquals(finalText,payload.at("/sceneArchiveResult/publication/story").asText());assertEquals(finalText,json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref LIKE 'story-experience:%'",String.class,sid,self)).path("text").asText());
     operations.create(request);operations.recover(sid,pid,"fixed-final-manuscript",0);assertEquals(1,finals.get());assertEquals(1,archiveCalls.get());
    }finally{ReflectionTestUtils.setField(operations,"sourceTimeReading",false);}
   }
 ObjectNode reviewedManuscript(String text){
  var result=json.createObjectNode();result.putObject("publication").put("story",text);result.putObject("timeProgress").putArray("milestones").addObject().putArray("elapsedPartsFromStart").addObject().put("amount",16).put("unit","HOUR");result.putArray("cast");return result;
 }
 @RepeatedTest(3) void focusedShortManuscriptFreezesTextAndClockAndRetainsFamilyInTheActualPanel()throws Exception{
  ReflectionTestUtils.setField(operations,"manuscriptReview",true);var finals=new AtomicInteger();String finalText=story+" 知秋是林野的母亲。",rough=story+" 这里误把亲缘称谓说错。";
  String biography="知秋是林野的母亲；祖父已经去世，父亲仍然健在。";
  jdbc.update("INSERT INTO character_creation(session_id,player_id,view_mode,question_1,question_2,question_3,status,birth_story) VALUES(?,?,'DREAMER','一','二','三','COMPLETED',?)",sid,pid,biography);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
   if(prompt.contains("聚焦定稿阶段")){
    finals.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals(rough,input.at("/paragraphs/0/text").asText());assertTrue(input.path("people").isArray());assertEquals(biography,input.path("originStory").asText());
    var result=reviewedManuscript(finalText);result.remove("publication");result.putArray("paragraphRevisions").addObject().put("paragraphId","P1").put("text",finalText);result.withArray("cast").addObject().put("roleId",near).put("name","知秋").putObject("relationToPlayer").put("kind","FAMILY").put("label","母亲").put("basis","主角原始身世已经明确亲缘");return reply(result);
   }
   if(prompt.contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals(finalText,input.path("finalStory").asText());assertEquals("FINAL_MANUSCRIPT_RECORD_ONLY",input.path("publicationMode").asText());assertEquals(57600,input.at("/chapterOutcome/elapsedSeconds").asLong());assertEquals("FAMILY",input.at("/manuscriptRelationships/0/kind").asText());
    var result=projection();result.putObject("publication").put("story","归档不得改成这份文本").putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",1).put("unit","SECOND");result.withObject("playerJournal").withArray("relationships").addObject().put("roleId",near).put("kind","ACQUAINTANCE").put("basis","归档遗漏亲缘");return reply(result);
   }
   proseCalls.incrementAndGet();assertEquals(biography,json.readTree(messages.get(1).get("content")).path("originStory").asText());var result=json.createObjectNode().put("story",rough).put("stop","RESOLVED").put("elapsedSeconds",57600);result.putArray("roleIds").add(near).add(far);return reply(result);
  }).when(model).chatForJson(anyList());
  var req=request("focused-short");assertEquals("COMPLETED",operations.create(req).get("status"));assertEquals(1,proseCalls.get());assertEquals(1,finals.get());assertEquals(1,archiveCalls.get());
  assertEquals("narrator："+finalText,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));assertEquals(57600L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  var family=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,self,"relationship:"+near));assertEquals("FAMILY",family.path("kind").asText());assertEquals("母亲",family.path("label").asText());
  operations.create(req);operations.recover(sid,pid,"focused-short",0);assertEquals(1,proseCalls.get());assertEquals(1,finals.get());assertEquals(1,archiveCalls.get());
 }
 @Test void interruptedFocusedReviewKeepsTheAuthorAndRoleRepliesAndRecoversOnlyTheReview()throws Exception{
  ReflectionTestUtils.setField(operations,"manuscriptReview",true);var finals=new AtomicInteger();var ready=new java.util.concurrent.atomic.AtomicBoolean(false);
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
   if(prompt.contains("聚焦定稿阶段")){finals.incrementAndGet();var result=reviewedManuscript(story);if(!ready.get())result.remove("cast");return reply(result);}
   if(prompt.contains("定稿归档阶段")){archiveCalls.incrementAndGet();return reply(projection());}
   proseCalls.incrementAndGet();var d=json.createObjectNode().put("story",story).put("stop","RESOLVED").put("elapsedSeconds",60);d.putArray("roleIds").add(near).add(far);return reply(d);
  }).when(model).chatForJson(anyList());
  var req=request("focused-recover");assertEquals("PARTIAL",operations.create(req).get("status"));assertEquals(0,archiveCalls.get());assertEquals(1,proseCalls.get());int roles=roleCalls.get();ready.set(true);
  assertEquals("COMPLETED",operations.recover(sid,pid,"focused-recover",0).get("status"));assertEquals(2,finals.get());assertEquals(1,proseCalls.get());assertEquals(roles,roleCalls.get());assertEquals(1,archiveCalls.get());
  assertEquals("narrator："+story,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void aMotherOmittedByTheFirstDraftGetsHerOwnIntentionsBeforeFinalPublicationAndArchiving(boolean omitRepeatedIntroduction)throws Exception{
  ReflectionTestUtils.setField(operations,"manuscriptReview",true);var finals=new AtomicInteger();var motherId=new java.util.concurrent.atomic.AtomicReference<String>();String prose="柳氏是林野的母亲，她说明自己的看法，林野听完后两人留在北亭。";
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");var input=json.readTree(messages.get(1).get("content"));
   if(prompt.contains("聚焦定稿阶段")){
    int step=finals.incrementAndGet();var result=reviewedManuscript(prose);var person=result.withArray("cast").addObject().put("name","柳氏").put("profile","林野母亲，有自己的生活与看法").put("narrativeTier","EXTRA");
    for(var known:input.path("people"))if("柳氏".equals(known.path("name").asText()))motherId.set(known.path("roleId").asText());
    if(step==2){assertNotNull(motherId.get());person.put("roleId",motherId.get());assertEquals(3,roleCalls.get(),"The new mother's own role API must run before revising the final manuscript");}
    person.putObject("relationToPlayer").put("kind","FAMILY").put("label","母亲").put("basis","已发表身世明确，母子当面交谈");return reply(result);
   }
   if(prompt.contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();assertEquals(2,finals.get());assertEquals(3,roleCalls.get());var result=json.createObjectNode().put("continuity","母子当面交谈，母亲没有被误认作陌生人。");
    for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions"))result.putArray(field);result.putObject("playerJournal");result.putArray("newCharacterExperiences").addObject().put("name","柳氏").put("experience","与儿子说明自己的看法").put("situation","仍在北亭，与儿子在一起");return reply(result);
   }
   int step=proseCalls.incrementAndGet();var result=json.createObjectNode().put("story",prose).put("stop","RESOLVED").put("elapsedSeconds",60);result.putArray("roleIds");
   if(step==2){
    for(var candidate:input.at("/castPlanning/candidates"))if("柳氏".equals(candidate.path("name").asText()))motherId.set(candidate.path("roleId").asText());
    assertNotNull(motherId.get());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='柳氏'",Integer.class,sid),"The retained candidate is not published world history");
    assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && m.get("content").contains(prose)),"Cast hydration must preserve the complete unpublished candidate as revision input");
    if(omitRepeatedIntroduction)result.withArray("roleIds").add(motherId.get());
    else result.putArray("newCharacters").addObject().put("name","柳氏").put("profile","林野母亲，有自己的生活与看法").put("narrativeTier","EXTRA");
   }return reply(result);
  }).when(model).chatForJson(anyList());
  assertEquals("COMPLETED",operations.create(request("focused-new-mother")).get("status"));assertEquals(2,proseCalls.get());assertEquals(2,finals.get());assertEquals(1,archiveCalls.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name='柳氏'",Integer.class,sid));
  var family=json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,self,"relationship:"+motherId.get()));assertEquals("FAMILY",family.path("kind").asText());
  operations.create(request("focused-new-mother"));operations.recover(sid,pid,"focused-new-mother",0);
  assertEquals(2,proseCalls.get());assertEquals(2,finals.get());assertEquals(1,archiveCalls.get());assertEquals(3,roleCalls.get());
 }
 @RepeatedTest(3) void correctedCenturyRecordIsPersistedOnceAcrossArchiveAndRepeatedRequests()throws Exception{
  ReflectionTestUtils.setField(operations,"manuscriptReview",true);var finals=new AtomicInteger();
  String longText=story+" 此后一百年过去，这一日的旧约始末仍留在档案中。";long century=3153600000L;
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String prompt=messages.get(0).get("content");
   if(prompt.contains("聚焦定稿阶段")){
    finals.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals(30,input.at("/authoredTimeProgress/elapsedParts/0/amount").asInt());
    var r=json.createObjectNode().put("chronologyChanged",false);r.putArray("paragraphRevisions");r.putArray("cast");
    r.putObject("timingCorrection").put("required",true).put("reason","原记录三十天遗漏正文的一百年").putArray("sourceQuotes").add("此后一百年过去");
    r.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",100).put("unit","YEAR");return reply(r);
   }
   if(prompt.contains("定稿归档阶段")){
    archiveCalls.incrementAndGet();var input=json.readTree(messages.get(1).get("content"));assertEquals(longText,input.path("finalStory").asText());assertEquals(century,input.at("/chapterOutcome/elapsedSeconds").asLong());
    var r=projection();r.putObject("timeProgress").put("elapsedSeconds",30*86400);return reply(r);
   }
   proseCalls.incrementAndGet();var d=json.createObjectNode().put("story",longText).put("stop","RESOLVED");d.putArray("roleIds").add(near).add(far);
   d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",30).put("unit","DAY");return reply(d);
  }).when(model).chatForJson(anyList());
  var req=request("corrected-century");var result=operations.create(req);assertEquals("COMPLETED",result.get("status"));
  assertEquals(century,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  assertEquals("narrator："+longText,jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=?",String.class,sid));
  var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,req.operationId()));
  assertEquals(30,payload.at("/sceneDraft/timeProgress/elapsedParts/0/amount").asInt(),"The raw manuscript remains available as failure evidence");
  assertEquals(century,payload.at("/sceneArchiveResult/elapsedSeconds").asLong());assertEquals(century,payload.at("/scenePassageResults/0/timeProgress/elapsedSeconds").asLong());
  operations.create(req);operations.recover(sid,pid,req.operationId(),0);
  assertEquals(century,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  assertEquals(1,proseCalls.get());assertEquals(1,finals.get());assertEquals(1,archiveCalls.get());
 }
   @AfterEach void cleanup(){
  for(String key:List.of("sceneStory","storyFirst","storyArchive","manuscriptReview"))ReflectionTestUtils.setField(operations,key,false);
  jdbc.update("DELETE FROM llm_operation_usage WHERE session_id=?",sid);
  for(String table:List.of("story_foreshadow","story_plot_event","session_checkpoint_archive","game_goal_rule","game_operation","game_runtime","session_checkpoint","npc_decision_log","npc_memory","npc_goal","player_memory","player_objective","story_summary","interaction_log","world_book","god_relationship","scene_connection","session_npc_alias","npc_plan","world_clock","role_knowledge"))jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",sid);
  jdbc.update("DELETE FROM character_creation WHERE session_id=?",sid);jdbc.update("DELETE FROM player_profile WHERE player_id=?",pid);jdbc.update("DELETE FROM game_session WHERE session_id=?",sid);jdbc.update("DELETE FROM game_scene WHERE session_id=?",sid);jdbc.update("DELETE FROM god_profile WHERE session_id=?",sid);
 }
}
