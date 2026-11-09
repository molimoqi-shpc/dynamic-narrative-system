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
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual public operation path, independent MySQL saves; model transport is replaced, never paid. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","audit.framed-story=true"})
class FramedStoryIT {
 @Autowired ConversationOperationService operations;@Autowired SessionStateService checkpoints;
 @Autowired JdbcTemplate jdbc;@Autowired StoryClockService clock;@Autowired GodProfileRepository actors;
 @Autowired MainModelService mainModels;
 @Autowired VitalStateService vitality;
 @Autowired GameSceneRepository scenes;@Autowired ItemStateService items;
 @Autowired SceneStoryService manuscript;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();final AtomicInteger plans=new AtomicInteger(),texts=new AtomicInteger(),roles=new AtomicInteger();
 String sid,pid,self,friend,seller,home,shop,ref;
 @BeforeEach void seed()throws Exception{
  for(String flag:List.of("sceneStory","storyFirst","storyArchive","sourceTimeReading","manuscriptReview","directResults"))ReflectionTestUtils.setField(operations,flag,true);
  String id=UUID.randomUUID().toString().substring(0,8);sid="frame_"+id;pid="frame_u_"+id;self="frame_p_"+id;friend="frame_f_"+id;seller="frame_s_"+id;home="frame_h_"+id;shop="frame_w_"+id;
  for(String role:List.of(self,friend,seller))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静',?)",role,sid,role.equals(self)?"主角甲":role.equals(friend)?"友人乙":"掌柜丙",role.equals(self)?"[\"家传银牌\"]":"[]");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'工作间',?,'上午，主角和友人在工作间。')",home,sid,json.writeValueAsString(List.of(friend)));
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'修理铺',?,'掌柜在自己的铺子。')",shop,sid,json.writeValueAsString(List.of(seller)));
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,?,'unused',?,?)",pid,self,pid,home,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode) VALUES(?,?,?,?,'PLAYING','DREAMER')",sid,pid,self,home);
  ref=items.owned(sid,actors.findById(self).orElseThrow()).get(0).ref();clock.store(sid,self,point(0,3,"MORNING"));checkpoints.initialize(sid,pid);
  doAnswer(call->{roles.incrementAndGet();return reply(Map.of("intention","按本人已知和当前情况协助","boundaries","保管不等于赠送","narrativeTier","EXTRA"));}).when(model).chatSubForJson(anyList());
 }
 ObjectNode point(long year,int day,String period){return json.createObjectNode().put("year",year).put("day",day).put("period",period);}
 LLMResponse reply(Object value)throws Exception{String raw=json.writeValueAsString(value);return new LLMResponse(raw,raw,0,0,0,0);}
 ConversationOperationService.Request request(String id){var r=jdbc.queryForMap("SELECT branch_epoch,revision FROM game_runtime WHERE session_id=?",sid);return new ConversationOperationService.Request(id,sid,pid,home,"按当前情况处理普通事务，重大选择再交给我。",null,((Number)r.get("branch_epoch")).longValue(),((Number)r.get("revision")).longValue(),true);}
 JsonNode payload(String id)throws Exception{return json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND operation_id=?",String.class,sid,id));}
 ObjectNode frame(int day,String period,boolean useSeller,String mode){
  var f=json.createObjectNode().put("stop","RESOLVED").put("continuity",mode.equals("LEND")?"主角与友人已访店、帮忙，银牌暂由友人保管，掌柜留店。":"银牌已归还，次日上午回访后返回工作间，掌柜留店。");f.set("endMoment",point(0,day,period));
  f.putArray("scenes").addObject().put("key","end").put("place","工作间").put("content",f.path("continuity").asText()).set("at",point(0,day,period));
  var ids=f.putArray("roleIds").add(self).add(friend);if(useSeller)ids.add(seller);
  var places=f.putObject("endingPlaces");places.putObject("home").put("locationName","工作间").put("opening","主角与友人回到工作间。");places.putObject("shop").put("locationName","修理铺").put("opening","掌柜留在自己铺中。");
  var cast=f.putArray("endingCast");cast.addObject().put("roleId",self).put("placeKey","home");cast.addObject().put("roleId",friend).put("placeKey","home");if(useSeller)cast.addObject().put("roleId",seller).put("placeKey","shop");
  var exp=f.putArray("roleExperiences");exp.addObject().put("roleId",friend).put("experience",f.path("continuity").asText()).put("situation",f.path("continuity").asText());if(useSeller)exp.addObject().put("roleId",seller).put("experience","与来客完成本次普通问答。").put("situation","仍在自己的修理铺，不曾跟他们回家。");
  f.putArray("itemOutcomes").addObject().put("itemRef",ref).put("name","家传银牌").put("state","HELD").put("holderId",mode.equals("LEND")?friend:self).put("mode",mode);
  return f;
 }
 @RepeatedTest(3) void independentSavesKeepFreeActionSecretsForTheAuthorAndEachRolesOwnMemory()throws Exception{
  for(String role:List.of(self,friend,seller))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,scene_id,location_name,learned_tick) VALUES(?,?,'privacy:own','本人经历',?,'PERSONAL',?,'',0)",sid,role,"OWN_SECRET_"+role,role.equals(seller)?shop:home);
  var called=new HashSet<String>();String secret="PLAYER_ONLY_PRIVATE_PLAN";
  doAnswer(call->{List<Map<String,String>> input=call.getArgument(0);var own=json.readTree(input.get(1).get("content"));String role=own.path("id").asText();called.add(role);String all=input.toString();
   assertFalse(all.contains(secret));assertFalse(all.contains("OWN_SECRET_"+self));assertTrue(all.contains("OWN_SECRET_"+role));
   assertFalse(all.contains("OWN_SECRET_"+(role.equals(friend)?seller:friend)));return reply(Map.of("intention","按自己已有目的正常回应"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{List<Map<String,String>> input=call.getArgument(0);assertTrue(input.toString().contains(secret));return reply(frame(3,"AFTERNOON",true,"HOLD"));}).when(model).chatForJson(anyList());
  doAnswer(call->LLMResponse.raw("下午，主角和友人核对了公开的消息，各人仍保有自己的打算，掌柜留在店里。",0,0,0)).when(model).chat(anyList());
  var r=request("private-action");var action=new ConversationOperationService.Request(r.operationId(),sid,pid,home,"我向友人问路，私下保留"+secret+"，没有向别人提起。",null,r.branchEpoch(),r.expectedRevision(),true);
  assertEquals("COMPLETED",operations.create(action).get("status"),payload("private-action").path("sceneIssue").asText());assertEquals(Set.of(friend,seller),called);
  int calls=mockingDetails(model).getInvocations().size();operations.create(action);operations.recover(sid,pid,"private-action",0);assertEquals(calls,mockingDetails(model).getInvocations().size());
 }
 @Test void directedSpeechReachesItsRecipientButNotTheRemoteRequestedCharacter()throws Exception{
  String words="DIRECTED_WORDS_ONLY_TO_FRIEND";var seen=new HashSet<String>();
  doAnswer(call->{List<Map<String,String>> input=call.getArgument(0);var own=json.readTree(input.get(1).get("content"));String role=own.path("id").asText();seen.add(role);
   assertEquals(role.equals(friend),input.toString().contains(words));if(role.equals(friend))assertEquals("PROPOSED_DIRECT_SPEECH",own.at("/incomingInteraction/delivery").asText());
   return reply(Map.of("intention","根据本人所知给出回应范围"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->reply(frame(3,"AFTERNOON",true,"HOLD"))).when(model).chatForJson(anyList());
  doAnswer(call->LLMResponse.raw("下午，两人问清了这件普通事情，掌柜依然留在自己的铺里。",0,0,0)).when(model).chat(anyList());
  var r=request("private-talk");var talk=new ConversationOperationService.Request(r.operationId(),sid,pid,home,words,new com.bs.narrative.dto.ActionRequest.DialogueInput(friend,words),r.branchEpoch(),r.expectedRevision(),true);
  assertEquals("COMPLETED",operations.create(talk).get("status"),payload("private-talk").path("sceneIssue").asText());assertEquals(Set.of(friend,seller),seen);
 }
 @Test void aLegacyPendingRoleRequestIsIsolatedOnRecoveryWithoutRepeatingItsSuccessfulPeer()throws Exception{
  var ready=new AtomicBoolean();var friendCalls=new AtomicInteger();var sellerCalls=new AtomicInteger();
  doAnswer(call->{List<Map<String,String>> input=call.getArgument(0);String role=json.readTree(input.get(1).get("content")).path("id").asText();
   if(role.equals(seller)){sellerCalls.incrementAndGet();if(!ready.get())throw new LLMApiException("模拟角色连接中断",false);assertFalse(input.toString().contains("OLD_PENDING_PLAYER_SECRET"));}
   else friendCalls.incrementAndGet();return reply(Map.of("intention","保留自己的目标并正常交谈"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->reply(frame(3,"AFTERNOON",true,"HOLD"))).when(model).chatForJson(anyList());
  doAnswer(call->LLMResponse.raw("下午，主角与友人完成了问询，各人回到自己的事情上。",0,0,0)).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("private-recover")).get("status"));int successfulPeers=friendCalls.get();
  var saved=(ObjectNode)payload("private-recover");var input=(ArrayNode)saved.path("sceneRoleInputs").path(seller);var old=(ObjectNode)json.readTree(input.get(1).path("content").asText());
  old.put("narrativeRequest","OLD_PENDING_PLAYER_SECRET").put("playerInput","OLD_PENDING_PLAYER_SECRET");old.remove("requestPrivacyVersion");((ObjectNode)input.get(1)).put("content",old.toString());((ObjectNode)input.get(2)).put("content","OLD_PENDING_PLAYER_SECRET");
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id='private-recover'",saved.toString(),sid);ready.set(true);
  assertEquals("COMPLETED",operations.recover(sid,pid,"private-recover",0).get("status"),payload("private-recover").path("sceneIssue").asText());assertEquals(successfulPeers,friendCalls.get());
  assertFalse(payload("private-recover").path("sceneRoleInputs").path(seller).toString().contains("OLD_PENDING_PLAYER_SECRET"));
 }
 @RepeatedTest(3) void independentSavesContinueAndKeepClockCustodyAndLocationsWithoutReviewCalls()throws Exception{
  doAnswer(call->{assertEquals(LLMRequestPolicy.STORY_FRAME,LLMRequestPolicy.current());List<Map<String,String>> messages=call.getArgument(0);var facts=json.readTree(messages.get(1).get("content"));plans.incrementAndGet();int day=facts.path("currentMoment").path("day").asInt();boolean second="AFTERNOON".equals(facts.path("currentMoment").path("period").asText());if(second)assertTrue(StoryAuthorText.last(messages).contains("FRAME") || messages.get(0).get("content").contains("STORY_FRAME"));return reply(frame(second?day+1:day,second?"MORNING":"AFTERNOON",true,second?"RETURN":"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();assertEquals(LLMRequestPolicy.STORY_TEXT,LLMRequestPolicy.current());List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));var prepared=json.readTree(messages.get(2).get("content"));boolean second=prepared.at("/endMoment/day").asInt()==4;
   assertEquals("assistant",messages.get(2).get("role"));assertEquals("工作间",json.readTree(messages.get(3).get("content")).at("/finalPlayerPlace/locationName").asText());
   assertEquals("DREAMER",input.path("viewMode").asText());
   if(second){assertEquals(3,input.at("/previousChapters/0/timeProgress/endMoment/day").asInt());assertEquals("AFTERNOON",input.at("/previousChapters/0/timeProgress/endMoment/period").asText());}
   if(!second)assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER'",Integer.class,sid),"Unpublished profiles must remain in the workspace");
   return LLMResponse.raw(second?"次日上午，主角甲向友人乙取回原来的银牌。二人回访掌柜丙后返回工作间，掌柜仍留在自己的铺子。":"上午两人动身，甲把银牌交乙临时保管。访店、帮忙后，下午回到工作间，掌柜留在铺中。",0,0,0);
  }).when(model).chat(anyList());
  var first=request("first");var a=operations.create(first);assertEquals("COMPLETED",a.get("status"),a.toString());assertEquals("AUTHOR_RESULT_V2",payload("first").path("storyResultMode").asText());
  assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());assertTrue(items.custody(sid,actors.findById(friend).orElseThrow()).stream().anyMatch(i->ref.equals(i.get("ref"))));
  var b=operations.create(request("second"));assertEquals("COMPLETED",b.get("status"),b.toString());assertEquals(4,clock.current(sid,self).path("day").asInt());assertEquals("MORNING",clock.current(sid,self).path("period").asText());
  assertEquals(86400L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));assertTrue(items.owned(sid,actors.findById(self).orElseThrow()).stream().anyMatch(i->i.ref().equals(ref)));assertFalse(scenes.findById(home).orElseThrow().getPresentGods().contains(seller));assertTrue(scenes.findById(shop).orElseThrow().getPresentGods().contains(seller));
  int p=plans.get(),t=texts.get(),r=roles.get();operations.create(first);operations.recover(sid,pid,"first",0);assertEquals(p,plans.get());assertEquals(t,texts.get());assertEquals(r,roles.get());assertEquals(2,texts.get());
 }
 @Test void aDurableManuscriptSurvivesWorkspaceFailureAndTheFollowingRoundStillRuns()throws Exception{
  var original=(ModelReplyJournal)ReflectionTestUtils.getField(manuscript,"replyJournal");
  var interruptOnce=new AtomicBoolean(true);
  var interrupted=new ModelReplyJournal(jdbc){
   @Override public void received(String session,ModelProtocol.Stage stage,String source,LLMResponse response,boolean repair){
    original.received(session,stage,source,response,repair);
    if(session.equals(sid) && stage==ModelProtocol.Stage.STORY_TEXT && interruptOnce.getAndSet(false))throw new org.springframework.dao.DataAccessResourceFailureException("After durable manuscript, before workspace acknowledgement");
   }
  };
  ReflectionTestUtils.setField(manuscript,"replyJournal",interrupted);
  try{
   doAnswer(call->{int n=plans.incrementAndGet();return reply(frame(n==1?3:4,n==1?"AFTERNOON":"MORNING",false,n==1?"LEND":"RETURN"));}).when(model).chatForJson(anyList());
   doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人完成事务后回到工作间，银牌依本次约定交接。",0,0,0);}).when(model).chat(anyList());
   var request=request("durable-manuscript");
   assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->operations.create(request));
   jdbc.update("UPDATE game_operation SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 120 SECOND) WHERE session_id=? AND operation_id=?",sid,request.operationId());
   assertEquals(1,texts.get());assertEquals("MORNING",clock.current(sid,self).path("period").asText());
   int previousRoles=roles.get();operations.read(sid,pid,request.operationId(),0L);assertEquals(1,texts.get());
   // Remove the fault injector; the operation workspace has no saved manuscript.
   ReflectionTestUtils.setField(manuscript,"replyJournal",original);
   var complete=operations.recover(sid,pid,request.operationId(),0);
   assertEquals("COMPLETED",complete.get("status"),complete.toString());assertEquals(1,texts.get());assertEquals(1,plans.get());assertEquals(previousRoles,roles.get());
   operations.create(request);assertEquals(1,texts.get());
   assertEquals("COMPLETED",operations.create(request("after-durable-recovery")).get("status"));
   assertEquals(2,texts.get());assertEquals(2,plans.get());assertEquals(4,clock.current(sid,self).path("day").asInt());
   assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
  }finally{ReflectionTestUtils.setField(manuscript,"replyJournal",original);}
 }
 @Test void aSecondSceneFailureReusesTheFirstSceneAndPublishesOnlyTheCompleteChapter()throws Exception{
  var ready=new AtomicBoolean();var firstSceneCalls=new AtomicInteger();
  doAnswer(call->{plans.incrementAndGet();var f=frame(3,"AFTERNOON",false,"LEND");var scene=f.withArray("scenes").get(0).deepCopy();
   ((ObjectNode)scene).put("key","visit").put("content","中午两人到铺中问清情况。").set("at",point(0,3,"NOON"));
   f.withArray("scenes").insert(0,scene);return reply(f);
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> messages=call.getArgument(0);JsonNode focus=json.readTree(messages.get(3).get("content"));
   if(focus.at("/selectedScene/key").asText().equals("visit")){firstSceneCalls.incrementAndGet();return LLMResponse.raw("中午两人到铺中问清情况。",0,0,0);}
   if(!ready.get())throw new LLMApiException("Second scene connection interrupted",false).withPartialResponse("午后两人", "length");
   assertTrue(messages.get(1).get("content").contains("中午两人到铺中问清情况。"));return LLMResponse.raw("下午两人完成事务回到工作间，银牌暂由友人保管。",0,0,0);
  }).when(model).chat(anyList());
  var request=request("second-scene-interruption");assertEquals("PARTIAL",operations.create(request).get("status"));
  assertTrue(payload(request.operationId()).path("story").asText().isBlank());assertEquals(1,firstSceneCalls.get());
  assertEquals("MORNING",clock.current(sid,self).path("period").asText());int previousRoles=roles.get();ready.set(true);
  var done=operations.recover(sid,pid,request.operationId(),0);assertEquals("COMPLETED",done.get("status"),done.toString());
  assertEquals(1,firstSceneCalls.get());assertEquals(3,texts.get());assertEquals(1,plans.get());assertEquals(previousRoles,roles.get());
  assertEquals("中午两人到铺中问清情况。\n\n下午两人完成事务回到工作间，银牌暂由友人保管。",payload(request.operationId()).path("story").asText());
  assertEquals(2,payload(request.operationId()).path("proseStyleVersion").asInt());assertEquals(2,payload(request.operationId()).at("/sceneFrameFacts/proseStyleVersion").asInt());
  operations.create(request);assertEquals(3,texts.get());assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
 }
 @Test void truncatedTextRecoveryReusesFrameAndIntentionsWithoutPublishingCandidate()throws Exception{
  var ready=new AtomicBoolean();doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();if(!ready.get())throw new LLMApiException("finish_reason=length",false).withPartialResponse("上午，主角将银牌交给友人保管。","length");return LLMResponse.raw("上午，主角将银牌交给友人保管。下午两人处理好事务，回到工作间。",0,0,0);}).when(model).chat(anyList());
  var req=request("recover-text");assertEquals("PARTIAL",operations.create(req).get("status"));assertEquals("MORNING",clock.current(sid,self).path("period").asText());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER'",Integer.class,sid));int p=plans.get(),r=roles.get();ready.set(true);
  var done=operations.recover(sid,pid,"recover-text",0);assertEquals("COMPLETED",done.get("status"),done.toString());assertEquals(p,plans.get());assertEquals(r,roles.get());assertEquals(2,texts.get());assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
 }
 static class StoryAuthorText{static String last(List<Map<String,String>> messages){return messages.get(messages.size()-1).get("content");}}
 @Test void interruptionBetweenSavingATruncatedManuscriptAndMarkingItCannotPublishTheFragment()throws Exception{
  String fragment="两人刚走出门，主角还没来得及";
  String complete="两人走出门后完成事务，下午返回工作间，银牌仍由友人暂管。";
  var ready=new AtomicBoolean();
  var original=(ModelReplyJournal)ReflectionTestUtils.getField(manuscript,"replyJournal");
  var interruptedMarker=new ModelReplyJournal(jdbc){
   @Override public void issue(String session,ModelProtocol.Stage stage,String source,String issue){
    if(session.equals(sid) && stage==ModelProtocol.Stage.STORY_TEXT && issue.equals("MODEL_OUTPUT_TRUNCATED"))throw new org.springframework.dao.DataAccessResourceFailureException("Simulated interruption before a second marker write");
    super.issue(session,stage,source,issue);
   }
  };
  ReflectionTestUtils.setField(manuscript,"replyJournal",interruptedMarker);
  try{
   doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
   doAnswer(call->{texts.incrementAndGet();if(!ready.get())throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");return LLMResponse.raw(complete,0,0,0);}).when(model).chat(anyList());
   try{operations.create(request("atomic-fragment"));}catch(org.springframework.dao.DataAccessResourceFailureException interrupted){/* Simulate losing the process after the reply commit. */}
   String durableIssue=jdbc.queryForObject("SELECT issue FROM model_reply_journal WHERE session_id=? AND stage='STORY_TEXT'",String.class,sid);
   jdbc.update("UPDATE game_operation SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 120 SECOND) WHERE session_id=? AND operation_id='atomic-fragment'",sid);
   ready.set(true);var done=operations.recover(sid,pid,"atomic-fragment",0);
   assertEquals("COMPLETED",done.get("status"));assertEquals(complete,payload("atomic-fragment").path("story").asText(),"A saved truncated reply must not become the finished chapter");
   assertEquals("INCOMPLETE:MODEL_OUTPUT_TRUNCATED",durableIssue);assertEquals(2,texts.get());assertEquals(1,plans.get());assertEquals(1,roles.get());
   var stored=jdbc.queryForMap("SELECT original_response,latest_response,issue FROM model_reply_journal WHERE session_id=? AND stage='STORY_TEXT'",sid);
   assertEquals(fragment,json.readTree(stored.get("original_response").toString()).path("content").asText());assertEquals(complete,json.readTree(stored.get("latest_response").toString()).path("content").asText());assertEquals("",stored.get("issue"));
  }finally{ReflectionTestUtils.setField(manuscript,"replyJournal",original);}
 }
 @Test void eachOperationPinsItsMainModelAndNextOperationUsesTheNewPreference()throws Exception{
  mainModels.change(sid,MainModelContext.PRO);
  doAnswer(call->{int n=plans.incrementAndGet();assertEquals(n==1?MainModelContext.PRO:MainModelContext.FLASH,MainModelContext.current());if(n==1)mainModels.change(sid,MainModelContext.FLASH);return reply(frame(n==1?3:4,n==1?"AFTERNOON":"MORNING",false,n==1?"LEND":"RETURN"));}).when(model).chatForJson(anyList());
  doAnswer(call->{int n=texts.incrementAndGet();assertEquals(n==1?MainModelContext.PRO:MainModelContext.FLASH,MainModelContext.current());return LLMResponse.raw("两人按各自意愿办完事务，回到工作间。",0,0,0);}).when(model).chat(anyList());
  try{
   assertEquals("COMPLETED",operations.create(request("model-one")).get("status"));assertEquals(MainModelContext.PRO,payload("model-one").path("mainModel").asText());assertNull(MainModelContext.current());
   assertEquals("COMPLETED",operations.create(request("model-two")).get("status"));assertEquals(MainModelContext.FLASH,payload("model-two").path("mainModel").asText());assertNull(MainModelContext.current());
  }finally{jdbc.update("DELETE FROM session_main_model WHERE session_id=?",sid);}
 }
 @Test void savedFrameWithRecoverableFieldAliasContinuesWithoutAnotherFrameCall()throws Exception{
  var candidate=frame(3,"AFTERNOON",false,"LEND");candidate.set("scene",candidate.remove("scenes"));String raw=candidate.toString();
  doAnswer(call->{plans.incrementAndGet();throw new LLMApiException("保存后读取中断",false).withPartialResponse(raw,"FORMAT");}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("下午两人办完事务回到工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("saved-frame")).get("status"));int previousRoles=roles.get();
  var done=operations.recover(sid,pid,"saved-frame",0);assertEquals("COMPLETED",done.get("status"),done.toString());
  assertEquals(1,plans.get());assertEquals(1,texts.get());assertEquals(previousRoles,roles.get());assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
  assertEquals("REPARSED_SAVED_FRAME",payload("saved-frame").at("/sceneMainRecoveries/0/method").asText());
 }
 @Test void thirtyConsecutiveOperationsKeepOneClockAndDoNotDuplicateImportedHistory()throws Exception{
  doAnswer(call->{int n=plans.incrementAndGet();return reply(frame(3+n,"MORNING",false,"HOLD"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("翌日上午，两人完成了这次普通事务，银牌仍由主角持有，两人留在工作间继续生活。",0,0,0);}).when(model).chat(anyList());
  for(int n=1;n<=30;n++){var result=operations.create(request("sequence-"+n));assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(3+n,clock.current(sid,self).path("day").asInt());}
  assertEquals(30,plans.get());assertEquals(30,texts.get());assertEquals(30L*86400,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
  var row=jdbc.queryForMap("SELECT COUNT(*) n,COUNT(DISTINCT state_changes) refs FROM world_book WHERE session_id=? AND event_type='CHARACTER_HISTORY'",sid);assertEquals(((Number)row.get("n")).longValue(),((Number)row.get("refs")).longValue());
 }
 @RepeatedTest(3) void instanceInsideSchemaEnvelopePublishesOnNewSavesWithoutRedrawingFrame()throws Exception{
  doAnswer(call->{plans.incrementAndGet();var wrapped=json.createObjectNode().put("type","object");wrapped.set("properties",frame(3,"AFTERNOON",false,"LEND"));return reply(wrapped);}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> messages=call.getArgument(0);var accepted=json.readTree(messages.get(2).get("content"));assertFalse(accepted.has("properties"));assertEquals("AFTERNOON",accepted.at("/endMoment/period").asText());return LLMResponse.raw("主角与友人办完事务，下午回到工作间，银牌暂交友人保管。",0,0,0);}).when(model).chat(anyList());
  var result=operations.create(request("wrapped-new"));assertEquals("COMPLETED",result.get("status"),result.toString());
  assertEquals(1,plans.get());assertEquals(1,texts.get());assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
 }
 @Test void retainedPreUpgradeWrappedFrameResumesWithoutRepayingForFrameOrRoles()throws Exception{
  var wrapped=json.createObjectNode().put("type","object");wrapped.set("properties",frame(3,"AFTERNOON",false,"LEND"));
  doAnswer(call->{plans.incrementAndGet();throw new LLMApiException("模拟旧版交接中断",false);}).when(model).chatForJson(anyList());
  var partial=operations.create(request("wrapped-saved"));assertEquals("PARTIAL",partial.get("status"));int previousRoles=roles.get();
  assertTrue(partial.get("ruleSnapshot").toString().contains("STORY_RESULT_UNAVAILABLE"));assertFalse(partial.get("ruleSnapshot").toString().contains("ROLE_DECISION_UNAVAILABLE"));
  // Reproduce the durable shape written by the old parser on the user's first turn.
  var saved=(ObjectNode)payload("wrapped-saved");saved.set("sceneDraft",wrapped);saved.put("sceneIssue","时点须为故事相对year、1至365的day及明确period；不从正文猜时间");saved.put("pendingKind","RESULT_UNRESOLVED");saved.remove("modelFailureReplies");
  saved.putArray("sceneMainCalls").addObject().put("frame","0").set("draft",wrapped.deepCopy());
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id=?",saved.toString(),sid,"wrapped-saved");
  doAnswer(call->{throw new AssertionError("A retained complete frame must not be regenerated");}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("主角与友人继续办完事务，下午回到工作间，银牌暂交友人保管。",0,0,0);}).when(model).chat(anyList());
  var result=operations.recover(sid,pid,"wrapped-saved",0);assertEquals("COMPLETED",result.get("status"),result.toString());
  assertEquals(previousRoles,roles.get());assertEquals(1,texts.get());assertEquals(1,plans.get());
  assertEquals("REPARSED_SAVED_FRAME",payload("wrapped-saved").at("/sceneMainRecoveries/0/method").asText());
  assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
  operations.recover(sid,pid,"wrapped-saved",0);assertEquals(1,texts.get());assertEquals(1,plans.get());
 }
 @Test void allKeyScenesAreWrittenAndInterruptedLaterSceneReusesPaidFirstScene()throws Exception{
  var ready=new AtomicBoolean();
  doAnswer(call->{plans.incrementAndGet();var f=frame(4,"MORNING",false,"LEND");f.withArray("scenes").insert(0,json.createObjectNode().put("key","journey").put("place","运河").put("content","下午动身并走完旅程").set("at",point(0,3,"AFTERNOON")));return reply(f);}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));
   if(input.path("sceneIndex").asInt()==0)return LLMResponse.raw("下午两人动身，主角把银牌交友人暂管，随后走完旅程。",0,0,0);
   assertEquals("journey",input.at("/alreadyWrittenScenes/0/sceneKey").asText());assertTrue(input.at("/alreadyWrittenScenes/0/story").asText().contains("走完旅程"));
   if(!ready.get())throw new LLMApiException("临时中断",false);
   return LLMResponse.raw("翌日上午，两人终于回到工作间，暂管的银牌仍在友人处。",0,0,0);
  }).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("multi-scene")).get("status"));assertEquals("MORNING",clock.current(sid,self).path("period").asText());assertEquals(3,clock.current(sid,self).path("day").asInt());int previousRoles=roles.get();ready.set(true);
  var result=operations.recover(sid,pid,"multi-scene",0);assertEquals("COMPLETED",result.get("status"),result.toString());
  assertEquals(3,texts.get(),"The already paid first scene must not be regenerated");assertEquals(1,plans.get());assertEquals(previousRoles,roles.get());
  var manuscript=payload("multi-scene").at("/sceneDraft/story").asText();assertTrue(manuscript.contains("走完旅程"));assertTrue(manuscript.contains("终于回到工作间"));assertEquals(4,clock.current(sid,self).path("day").asInt());
 }
 @RepeatedTest(2) void oneAuthorFrameFormatMistakeRepairsTheSavedCandidateAndPublishesWithoutManualRecovery()throws Exception{
  var bad=frame(3,"AFTERNOON",false,"LEND");bad.put("roleIds","不是数组");String retained=bad.toString();
  doAnswer(call->{int attempt=plans.incrementAndGet();if(attempt==1)return reply(bad);
   List<Map<String,String>> messages=call.getArgument(0);
   assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && retained.equals(m.get("content"))),"Repair must receive the exact saved candidate");
   assertTrue(messages.get(messages.size()-1).get("content").contains("只修复同一候选"));
   return reply(frame(3,"AFTERNOON",false,"LEND"));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人完成事务，下午返回工作间，银牌仍由友人暂管。",0,0,0);}).when(model).chat(anyList());
  var result=operations.create(request("author-format"));assertEquals("COMPLETED",result.get("status"),result.toString());
  assertEquals(2,plans.get());assertEquals(1,texts.get());assertEquals(1,roles.get());
  assertEquals(1,payload("author-format").at("/sceneFrameFormatRepairs/0").asInt());
  assertEquals("AFTERNOON",clock.current(sid,self).path("period").asText());
  int before=plans.get();operations.recover(sid,pid,"author-format",0);assertEquals(before,plans.get());assertEquals(1,texts.get());
 }
 @Test void persistentAuthorFrameFormatMistakeStopsAfterOneRepairWithoutRedrawingRolesOrPublishing()throws Exception{
  doAnswer(call->{plans.incrementAndGet();var bad=frame(3,"AFTERNOON",false,"LEND");bad.put("roleIds","仍然不是数组");return reply(bad);}).when(model).chatForJson(anyList());
  var result=operations.create(request("author-format-persistent"));assertEquals("PARTIAL",result.get("status"),result.toString());
  assertEquals(2,plans.get());assertEquals(1,roles.get());assertEquals(0,texts.get());
  assertEquals(1,payload("author-format-persistent").at("/sceneFrameFormatRepairs/0").asInt());
  assertEquals(0,payload("author-format-persistent").path("scenePassages").size());assertEquals("MORNING",clock.current(sid,self).path("period").asText());
 }
 @Test void asynchronousAuthorFrameRepairHandsOffAndFinishesTheOriginalOperation()throws Exception{
  doAnswer(call->{if(plans.incrementAndGet()==1){var bad=frame(3,"AFTERNOON",false,"LEND");bad.put("roleIds","不是数组");return reply(bad);}return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人下午返回工作间，银牌暂交友人保管。",0,0,0);}).when(model).chat(anyList());
  operations.submit(request("author-format-async"));
  try{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(()->assertEquals("COMPLETED",operations.read(sid,pid,"author-format-async",0L).get("status")));}
  finally{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(operations::workersIdle);}
  assertEquals(2,plans.get());assertEquals(1,roles.get());assertEquals(1,texts.get());
 }
 @Test void authorTransportFailureIsNotAutomaticallyRetriedAsAFormatRepair()throws Exception{
  doAnswer(call->{plans.incrementAndGet();throw new LLMApiException("DeepSeek timeout",false);}).when(model).chatForJson(anyList());
  var result=operations.create(request("author-timeout"));assertEquals("PARTIAL",result.get("status"));
  assertEquals(1,plans.get());assertEquals(1,roles.get());assertEquals(0,texts.get());
  assertTrue(payload("author-timeout").path("sceneFrameFormatRepairs").isMissingNode());
 }
 @RepeatedTest(2) void truncatedRoleRepliesRepairTheSameCandidateBeforeTheAuthorContinues(RepetitionInfo repetition)throws Exception{
  String fragment=repetition.getCurrentRepetition()==1?"{\"intention\":\"愿按眼前情况协助\",\"thought\":":"{\"intention\":\"愿按眼前情况协助\",\"narrativeTier\":\"EXTRA\"}";
  doAnswer(call->{int count=roles.incrementAndGet();if(count==1)throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");
   List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && fragment.equals(m.get("content"))),"The paid role candidate must be supplied to its repair");
   return reply(Map.of("intention","愿按眼前情况协助","boundaries","仍保留原来的立场","narrativeTier","EXTRA"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人完成事务，下午返回工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  var result=operations.create(request("role-truncated"));assertEquals("COMPLETED",result.get("status"),String.valueOf(result.get("pendingKind")));
  assertEquals(2,roles.get(),"Even a parseable length-limited reply must be completed first");assertEquals(1,plans.get());assertEquals(1,texts.get());
  var stored=jdbc.queryForMap("SELECT original_response,latest_response,issue,repair_count FROM model_reply_journal WHERE session_id=? AND stage='ROLE'",sid);
  assertEquals(fragment,json.readTree(stored.get("original_response").toString()).path("content").asText());assertEquals("",stored.get("issue"));assertEquals(1,((Number)stored.get("repair_count")).intValue());
  assertTrue(payload("role-truncated").path("sceneDirectionAttempts").get(0).path("incomplete").asBoolean());
  assertEquals(1,payload("role-truncated").at("/sceneRoleFormatRepairs/0").asInt());
  operations.recover(sid,pid,"role-truncated",0);assertEquals(2,roles.get());assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @Test void repeatedRoleTruncationStopsAfterOneAutomaticRepairAndRetainsTheOriginal()throws Exception{
  String fragment="{\"intention\":\"仍愿协助\",";
  doAnswer(call->{roles.incrementAndGet();throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");}).when(model).chatSubForJson(anyList());
  var result=operations.create(request("role-truncated-twice"));assertEquals("PARTIAL",result.get("status"));assertEquals("MODEL_OUTPUT_TRUNCATED",result.get("pendingKind"));
  assertEquals(2,roles.get());verify(model,never()).chatForJson(anyList());verify(model,never()).chat(anyList());
  var stored=jdbc.queryForMap("SELECT original_response,issue FROM model_reply_journal WHERE session_id=? AND stage='ROLE'",sid);
  assertEquals(fragment,json.readTree(stored.get("original_response").toString()).path("content").asText());assertEquals("INCOMPLETE:MODEL_OUTPUT_TRUNCATED",stored.get("issue"));
  assertEquals(0,payload("role-truncated-twice").path("scenePassages").size());assertEquals("MORNING",clock.current(sid,self).path("period").asText());
 }
 void twoPresentRoles()throws Exception{
  jdbc.update("UPDATE game_scene SET present_gods=? WHERE session_id=? AND scene_id=?",json.writeValueAsString(List.of(friend,seller)),sid,home);
  jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE session_id=? AND scene_id=?",sid,shop);checkpoints.refreshCurrent(sid,pid);
 }
 @Test void oneTruncatedRoleDoesNotRegenerateTheOtherSuccessfulRole()throws Exception{
  twoPresentRoles();String fragment="{\"intention\":\"按本人意愿协助\",";
  doAnswer(call->{int count=roles.incrementAndGet();if(count==2)throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");
   if(count>2){List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && fragment.equals(m.get("content"))));}
   return reply(Map.of("intention","按本人意愿协助","narrativeTier","EXTRA"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",true,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人处理完事务回到工作间，掌柜留在修理铺。",0,0,0);}).when(model).chat(anyList());
  var result=operations.create(request("role-truncated-peers"));assertEquals("COMPLETED",result.get("status"),String.valueOf(result.get("pendingKind")));
  assertEquals(3,roles.get());assertEquals(1,plans.get());assertEquals(1,texts.get());
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM model_reply_journal WHERE session_id=? AND stage='ROLE'",Integer.class,sid));
 }
 @Test void failedTransportDuringRoleRepairKeepsTheCandidateForAnExplicitRecovery()throws Exception{
  String fragment="{\"intention\":\"愿暂管银牌\",";
  doAnswer(call->{int count=roles.incrementAndGet();if(count==1)throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");
   if(count==2)throw new LLMApiException("network timeout",false);
   List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && fragment.equals(m.get("content"))));return reply(Map.of("intention","愿暂管银牌","narrativeTier","EXTRA"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("下午两人返回工作间，银牌仍由友人暂管。",0,0,0);}).when(model).chat(anyList());
  var partial=operations.create(request("role-repair-outage"));assertEquals("PARTIAL",partial.get("status"));assertEquals("MODEL_TIMEOUT",partial.get("pendingKind"));assertEquals(2,roles.get());assertEquals(0,plans.get());
  assertEquals("COMPLETED",operations.recover(sid,pid,"role-repair-outage",0).get("status"));assertEquals(3,roles.get());assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @Test void asynchronousRoleTruncationRepairsAndPublishesTheOriginalOperation()throws Exception{
  String fragment="{\"intention\":\"愿意帮忙\",";
  doAnswer(call->{if(roles.incrementAndGet()==1)throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");return reply(Map.of("intention","愿意帮忙","narrativeTier","EXTRA"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人完成事务，下午返回工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  operations.submit(request("role-truncated-async"));
  try{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(()->assertEquals("COMPLETED",operations.read(sid,pid,"role-truncated-async",0L).get("status")));}
  finally{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(operations::workersIdle);}
  assertEquals(2,roles.get());assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @Test void mixedRoleTruncationAndNetworkFailureRetainBothUntilExplicitRecovery()throws Exception{
  twoPresentRoles();String fragment="{\"intention\":\"愿意帮忙\",";
  doAnswer(call->{int n=roles.incrementAndGet();if(n==1)throw new LLMApiException("finish_reason=length",false).withPartialResponse(fragment,"length");if(n==2)throw new LLMApiException("network timeout",false);return reply(Map.of("intention","愿意帮忙","narrativeTier","EXTRA"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",true,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人处理完事务返回工作间，掌柜留在铺中。",0,0,0);}).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("role-mixed-failure")).get("status"));assertEquals(2,roles.get());assertEquals(0,plans.get());
  assertTrue(payload("role-mixed-failure").path("sceneRoleFormatRepairs").isMissingNode());
  assertEquals(fragment,json.readTree(jdbc.queryForObject("SELECT original_response FROM model_reply_journal WHERE session_id=? AND stage='ROLE'",String.class,sid)).path("content").asText());
  assertEquals("COMPLETED",operations.recover(sid,pid,"role-mixed-failure",0).get("status"));assertEquals(4,roles.get());assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @RepeatedTest(2) void journalOnlyRoleRecoveryUsesCompletionMarkerAndParsedPayload(RepetitionInfo repetition)throws Exception{
  boolean incomplete=repetition.getCurrentRepetition()==1;
  String candidate="{\"intention\":\"愿意帮忙\",\"narrativeTier\":\"EXTRA\"}";
  doAnswer(call->{int n=roles.incrementAndGet();if(incomplete && n==1)throw new LLMApiException("finish_reason=length",false).withPartialResponse(candidate,"length");return new LLMResponse("外壳说明文字",candidate,0,0,0,0);}).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人处理完事务，下午返回工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  var original=(ModelReplyJournal)ReflectionTestUtils.getField(operations,"modelReplies");var interrupted=new AtomicBoolean();
  var failing=new ModelReplyJournal(jdbc){
   void interrupt(ModelProtocol.Stage stage){if(stage==ModelProtocol.Stage.ROLE && interrupted.compareAndSet(false,true))throw new org.springframework.dao.DataAccessResourceFailureException("Simulated interruption after durable role reply, before workspace save");}
   @Override public void received(String session,ModelProtocol.Stage stage,String source,LLMResponse response,boolean repair){original.received(session,stage,source,response,repair);interrupt(stage);}
   @Override public void receivedIncomplete(String session,ModelProtocol.Stage stage,String source,LLMResponse response,boolean repair,String issue){original.receivedIncomplete(session,stage,source,response,repair,issue);interrupt(stage);}
  };
  ReflectionTestUtils.setField(operations,"modelReplies",failing);
  try{assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->operations.create(request("role-journal-only")));}
  finally{ReflectionTestUtils.setField(operations,"modelReplies",original);}
  assertEquals(0,payload("role-journal-only").path("sceneDirectionAttempts").size());assertEquals(1,roles.get());assertEquals(0,plans.get());
  jdbc.update("UPDATE game_operation SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 120 SECOND) WHERE session_id=? AND operation_id='role-journal-only'",sid);
  assertEquals("COMPLETED",operations.recover(sid,pid,"role-journal-only",0).get("status"));
  assertEquals(incomplete?2:1,roles.get(),"An incomplete journal must be completed; a complete parsed payload must be reused");assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @RepeatedTest(2) void parseableTransportTruncatedFramesCannotBypassCompletionOnRecovery(RepetitionInfo repetition)throws Exception{
  boolean timeout=repetition.getCurrentRepetition()==2;
  String candidate=frame(3,"AFTERNOON",false,"LEND").toString();
  doAnswer(call->{int n=plans.incrementAndGet();if(n==1)throw new LLMApiException(timeout?"network timeout":"finish_reason=length",false).withPartialResponse(candidate,timeout?"":"length");
   List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().anyMatch(m->"assistant".equals(m.get("role")) && candidate.equals(m.get("content"))));return reply(frame(3,"AFTERNOON",false,"LEND"));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人处理完事务，下午返回工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("frame-transport-fragment")).get("status"));assertEquals(1,plans.get());assertEquals(1,roles.get());assertEquals(0,texts.get());
  assertEquals("MORNING",clock.current(sid,self).path("period").asText());
  assertEquals("COMPLETED",operations.recover(sid,pid,"frame-transport-fragment",0).get("status"));
  assertEquals(2,plans.get(),"Transport-incomplete frame must be completed rather than promoted from raw failure cache");assertEquals(1,roles.get());assertEquals(1,texts.get());
  operations.recover(sid,pid,"frame-transport-fragment",0);assertEquals(2,plans.get());assertEquals(1,texts.get());
 }
 @Test void selectedSituationalTalentReachesFrameAndProseWithoutLeakingToOtherRolesAndSurvivesRewind()throws Exception{
  String description="【情境天赋】更容易从轻敲木器的声音发现空腔。发挥时机：近距离实际检查。作用边界：不知空腔内容，嘈杂时容易受干扰。";
  jdbc.update("INSERT INTO talent(session_id,god_id,talent_name,talent_description,power_level,is_selected,created_at) VALUES(?,?,?,?,2,true,CURRENT_TIMESTAMP)",sid,self,"听木知隙",description);
  jdbc.update("INSERT INTO talent(session_id,god_id,talent_name,talent_description,power_level,is_selected,created_at) VALUES(?,?,?,?,2,false,CURRENT_TIMESTAMP)",sid,self,"未选天赋","【情境天赋】不应激活的另一候选");
  jdbc.update("INSERT INTO talent(session_id,god_id,talent_name,talent_description,power_level,is_selected,created_at) VALUES(?,?,?,?,2,true,CURRENT_TIMESTAMP)",sid+"_other",self,"外档天赋","【情境天赋】不能混入的其他存档能力");
  doAnswer(call->{roles.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);assertFalse(m.toString().contains("听木知隙"),"Another role cannot learn private player talents from the author context");return reply(Map.of("intention","愿意协助检查","narrativeTier","EXTRA"));}).when(model).chatSubForJson(anyList());
  doAnswer(call->{plans.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));var selected=input.path("characterDossiers").path(self).at("/identity/talents");
   assertEquals(1,selected.size());assertEquals("听木知隙",selected.get(0).path("name").asText());assertEquals(description,selected.get(0).path("description").asText());assertEquals("NARRATIVE",selected.get(0).path("effectKind").asText());assertTrue(input.path("characterDossiers").path(friend).at("/identity/talents").isMissingNode());
   return reply(frame(3,"AFTERNOON",false,"LEND"));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));assertEquals(description,input.path("characterDossiers").path(self).at("/identity/talents/0/description").asText());assertFalse(input.toString().contains("外档天赋"));assertFalse(input.toString().contains("未选天赋"));return LLMResponse.raw("林舟靠近旧木器，轻敲后听出了声响的差别。友人点灯相助，两人拆开松动处后才确认里面的情况。下午回到工作间，银牌由友人暂管。",0,0,0);}).when(model).chat(anyList());
  assertEquals("COMPLETED",operations.create(request("talent-first")).get("status"));
  checkpoints.restore(sid,0);
  assertEquals("COMPLETED",operations.create(request("talent-rewound")).get("status"));assertEquals(2,plans.get());assertEquals(2,texts.get());assertEquals(2,roles.get());
 }
 @RepeatedTest(3) void aFreshActionWithTheSameIdAfterRewindCannotReuseAnotherBranchsAuthorReplies()throws Exception{
  doAnswer(call->{plans.incrementAndGet();return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{int n=texts.incrementAndGet();return LLMResponse.raw(n==1?"第一条分支里，两人决定查问掌柜。下午他们返回工作间，银牌暂由友人保管。":"回溯后的新分支里，两人决定先查门框。下午他们返回工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  assertEquals("COMPLETED",operations.create(request("same-action")).get("status"));
  checkpoints.restore(sid,0);
  assertEquals("COMPLETED",operations.create(request("same-action")).get("status"));
  assertEquals(2,roles.get());assertEquals(2,plans.get(),"The author frame belongs to its branch even if action IDs and supplied story data match");assertEquals(2,texts.get(),"A fresh branch cannot silently reuse the discarded branch's prose");
  String current=jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND branch_epoch=(SELECT branch_epoch FROM game_runtime WHERE session_id=?) AND operation_id='same-action'",String.class,sid,sid);
  assertTrue(json.readTree(current).path("story").asText().contains("回溯后的新分支"));
  assertEquals("COMPLETED",operations.create(request("same-action")).get("status"));assertEquals(2,plans.get());assertEquals(2,texts.get());assertEquals(2,roles.get());
 }
 @Test void restoringAnUnfinishedMultiSceneCandidateReusesItsPaidCompletedScene()throws Exception{
  String first="第一场中，两人已经走过运河，查清桥边的情况。";String second="次日上午，两人回到工作间，银牌仍由友人保管。";var ready=new AtomicBoolean();
  doAnswer(call->{plans.incrementAndGet();var f=frame(4,"MORNING",false,"LEND");f.withArray("scenes").insert(0,json.createObjectNode().put("key","journey").put("place","运河").put("content","走过运河并查清桥边情况").set("at",point(0,3,"AFTERNOON")));return reply(f);}).when(model).chatForJson(anyList());
  doAnswer(call->{int n=texts.incrementAndGet();if(n==1)return LLMResponse.raw(first,0,0,0);if(!ready.get())throw new LLMApiException("finish_reason=length",false).withPartialResponse("次日上午，两人刚要","length");return LLMResponse.raw(second,0,0,0);}).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(request("restore-manuscript")).get("status"));assertEquals(1,plans.get());assertEquals(1,roles.get());assertEquals(2,texts.get());
  checkpoints.restore(sid,1);var rt=jdbc.queryForMap("SELECT branch_epoch,active_operation FROM game_runtime WHERE session_id=?",sid);String restored=rt.get("active_operation").toString();assertNotEquals("restore-manuscript",restored);ready.set(true);
  var result=operations.recover(sid,pid,restored,((Number)rt.get("branch_epoch")).longValue());assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(1,plans.get());assertEquals(1,roles.get());assertEquals(3,texts.get(),"The paid first scene belongs to this exact restored candidate and must not be regenerated");
  var current=json.readTree(jdbc.queryForObject("SELECT payload FROM game_operation WHERE session_id=? AND branch_epoch=? AND operation_id=?",String.class,sid,rt.get("branch_epoch"),restored));assertTrue(current.path("story").asText().contains(first));assertTrue(current.path("story").asText().contains(second));
 }
 @Test void oneRoleFormatMistakeRepairsTheSameCandidateAndContinuesAutomatically()throws Exception{
  var calls=new AtomicInteger();doAnswer(call->{
   if(calls.incrementAndGet()==1)return reply(Map.of("intention",Map.of("text","愿意暂管银牌")));
   List<Map<String,String>> m=call.getArgument(0);assertTrue(m.stream().anyMatch(x->"assistant".equals(x.get("role")) && x.get("content").contains("愿意暂管银牌")));
   return reply(Map.of("intention","愿意暂管银牌","narrativeTier","EXTRA"));
  }).when(model).chatSubForJson(anyList());
  doAnswer(call->reply(frame(3,"AFTERNOON",false,"LEND"))).when(model).chatForJson(anyList());
  doReturn(LLMResponse.raw("下午两人办完事务，银牌暂由友人保管。",0,0,0)).when(model).chat(anyList());
  var result=operations.create(request("auto-format"));assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,calls.get());assertEquals(1,payload("auto-format").at("/sceneRoleFormatRepairs/0").asInt());
 }
 ConversationOperationService.Request withMode(String id,String mode){var r=request(id);return new ConversationOperationService.Request(r.operationId(),r.sessionId(),r.playerId(),r.sceneId(),r.playerText(),r.dialogue(),r.branchEpoch(),r.expectedRevision(),r.storyMode(),mode);}
 @Test void normalIsTheDefaultAndModeGuidanceReachesTheActualManuscriptRequest()throws Exception{
  doAnswer(call->{plans.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);assertEquals("NORMAL",json.readTree(m.get(1).get("content")).path("narrativeMode").asText());return reply(frame(3,"AFTERNOON",false,"LEND"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var focus=json.readTree(m.get(m.size()-1).get("content"));assertEquals("NORMAL",focus.path("narrativeMode").asText());assertTrue(focus.path("lengthGuidance").asText().contains("至少500字"));assertTrue(focus.path("lengthGuidance").asText().contains("不是每个分场"));assertTrue(m.get(0).get("content").contains("去模板腔"));return LLMResponse.raw("下午两人处理完事务，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  var original=request("mode-default");assertEquals("COMPLETED",operations.create(original).get("status"));assertEquals("NORMAL",payload("mode-default").path("narrativeMode").asText());
  // Adding the now-explicit default to an old client retry must not replay its action.
  assertEquals("COMPLETED",operations.create(withMode("mode-default","NORMAL")).get("status"));assertEquals(1,plans.get());assertEquals(1,texts.get());
 }
 @Test void longModeIsFrozenDuringRecoveryAndTheNextActionCanUseNormal()throws Exception{
  var ready=new AtomicBoolean();
  doAnswer(call->{int n=plans.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);assertEquals(n==1?"LONG":"NORMAL",json.readTree(m.get(1).get("content")).path("narrativeMode").asText());return reply(frame(n==1?3:4,n==1?"AFTERNOON":"MORNING",false,n==1?"LEND":"RETURN"));}).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var focus=json.readTree(m.get(m.size()-1).get("content"));assertEquals(plans.get()==1?"LONG":"NORMAL",focus.path("narrativeMode").asText());
   if(!ready.get())throw new LLMApiException("模拟正文请求中断",false);return LLMResponse.raw("两人处理完事务，回到工作间，银牌按约交接。",0,0,0);}).when(model).chat(anyList());
  assertEquals("PARTIAL",operations.create(withMode("mode-long","LONG")).get("status"));int previousRoles=roles.get();
  assertThrows(RuntimeException.class,()->operations.create(withMode("mode-long","NORMAL")),"A reused operation ID cannot silently switch its frozen preference");
  ready.set(true);assertEquals("COMPLETED",operations.recover(sid,pid,"mode-long",0).get("status"));assertEquals(previousRoles,roles.get());assertEquals(1,plans.get());assertEquals("LONG",payload("mode-long").path("narrativeMode").asText());
  assertEquals("COMPLETED",operations.create(withMode("mode-next","NORMAL")).get("status"));assertEquals("NORMAL",payload("mode-next").path("narrativeMode").asText());
 }
 @Test void aNormalPlotLookupReturnsItsEmptyResultThenPublishesTheSameOperation()throws Exception{
  doAnswer(call->{int n=plans.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));
   if(n==1)return reply(Map.of("plotRequests",List.of(Map.of("keyword","尚无记录的旧事"))));
   assertEquals(1,input.path("plotResults").size());assertEquals("NO_RESULTS",input.at("/storyQueryFeedback/0/status").asText());return reply(frame(3,"AFTERNOON",false,"LEND"));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人核对了现有资料，下午回到工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  var result=operations.create(request("query-normal"));assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,plans.get());assertEquals(1,texts.get());assertEquals(1,payload("query-normal").path("scenePlotQueries").size());
 }
 @Test @Timeout(15) void repeatedCachedLookupStopsWithoutDuplicatingResultsAndRecoveryCanActuallyAdvance()throws Exception{
  var ready=new AtomicBoolean();doAnswer(call->{plans.incrementAndGet();if(plans.get()>6)throw new AssertionError("Repeated query must not loop indefinitely");
   List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));
   if(ready.get()){assertEquals(1,input.at("/queryBoundaryFeedback/resumeAttempt").asInt());return reply(frame(3,"AFTERNOON",false,"LEND"));}
   return reply(Map.of("plotRequests",List.of(Map.of("keyword","不存在于历史页的线索"))));
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw("两人承接原先的事务，下午回到工作间，银牌暂由友人保管。",0,0,0);}).when(model).chat(anyList());
  var partial=operations.create(request("query-repeat"));assertEquals("PARTIAL",partial.get("status"),partial.toString());var saved=payload("query-repeat");
  assertEquals(1,saved.path("scenePlotQueries").size());assertEquals(1,saved.at("/sceneFrameFacts/plotResults").size());assertEquals("ALREADY_NO_RESULTS",saved.at("/sceneFrameFacts/storyQueryFeedback/0/status").asText());assertTrue(saved.path("sceneDraft").has("plotRequests"));assertEquals(0,texts.get());
  // Re-reading an already-paid query is zero model calls. It still must hit the
  // no-progress boundary instead of spinning forever on the cached candidate.
  var cached=(ObjectNode)saved;cached.remove(List.of("sceneQueryBoundaryFrame","sceneIssue"));cached.withObject("sceneFrameFacts").remove("queryBoundaryFeedback");
  jdbc.update("UPDATE game_operation SET payload=? WHERE session_id=? AND operation_id=?",cached.toString(),sid,"query-repeat");
  int before=plans.get();assertEquals("PARTIAL",operations.recover(sid,pid,"query-repeat",0).get("status"));assertEquals(before,plans.get());
  assertEquals(0,payload("query-repeat").path("sceneQueryBoundaryFrame").asInt(-1),"No-progress marker must survive the transaction and allow a fresh continuation");
  ready.set(true);var result=operations.recover(sid,pid,"query-repeat",0);
  assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(before+1,plans.get());assertEquals(1,texts.get());assertEquals(1,payload("query-repeat").path("scenePlotQueries").size());
 }
 @Test @Timeout(20) void repeatedRoleAndMechanicsReferencesHaveAFiniteRecoverableHandoff()throws Exception{
  for(String kind:List.of("ROLE","MECHANICS")){
   var ready=new AtomicBoolean();var calls=new AtomicInteger();String operation="reference-repeat-"+kind;
   doAnswer(call->{calls.incrementAndGet();if(calls.get()>5)throw new AssertionError("A repeated reference must reuse its saved reply and stop locally");
    List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));
    if(ready.get()){assertEquals(1,input.at("/queryBoundaryFeedback/resumeAttempt").asInt());var completed=frame(3,kind.equals("ROLE")?"AFTERNOON":"EVENING",false,"LEND");for(var contest:input.path("serverContests"))if("RESOLVED".equals(contest.at("/facts/status").asText()))completed.withArray("contestResolutions").addObject().put("fingerprint",contest.at("/facts/fingerprint").asText()).put("occurred",false);return reply(completed);}
    return reply(kind.equals("ROLE")?Map.of("roleRequests",List.of(self)):Map.of("mechanics",List.of(Map.of("actorId",self,"targetId",friend,"kind","ATTACK","statKey","DOU"))));
   }).when(model).chatForJson(anyList());
   doReturn(LLMResponse.raw("两人承接之前的事务，核清情况后回到工作间，银牌暂由友人保管。",0,0,0)).when(model).chat(anyList());
   var partial=operations.create(request(operation));assertEquals("PARTIAL",partial.get("status"),partial.toString());var saved=payload(operation);
   assertEquals(0,saved.path("sceneQueryBoundaryFrame").asInt(-1));assertTrue(saved.path("sceneDraft").has(kind.equals("ROLE")?"roleRequests":"mechanics"));
   if(kind.equals("ROLE"))assertEquals("AVAILABLE",saved.at("/sceneFrameFacts/roleRequestResults").path(self).asText());
   else assertEquals(1,saved.at("/sceneFrameFacts/serverContests").size(),"Repeated formula lookups must not duplicate outcomes or damage");
   int previousCalls=calls.get(),previousRoles=roles.get();ready.set(true);var done=operations.recover(sid,pid,operation,0);assertEquals("COMPLETED",done.get("status"),kind+" "+payload(operation).path("sceneIssue")+" "+payload(operation).path("sceneArchiveIssue"));
   assertEquals(previousCalls+1,calls.get());assertEquals(previousRoles,roles.get());
  }
 }
 @RepeatedTest(2) void aMissingResultFieldCanBeRepairedWithoutRewritingTheCompletedManuscript(RepetitionInfo repetition)throws Exception{
  boolean needsSecondRepair=repetition.getCurrentRepetition()==2;
  String manuscript="两人原本有过交手的念头，最后各自收了手，没有出招，也没有受伤。下午他们回到工作间，银牌暂由友人保管。";
  doAnswer(call->{int count=plans.incrementAndGet();List<Map<String,String>> m=call.getArgument(0);var input=json.readTree(m.get(1).get("content"));assertTrue(m.get(0).get("content").contains("contestResolutions"));
   if(count==1)return reply(Map.of("mechanics",List.of(Map.of("actorId",self,"targetId",friend,"kind","ATTACK","statKey","DOU"))));
   var completed=frame(3,"AFTERNOON",false,"LEND");
   if(count>2){assertEquals(manuscript,input.at("/resultRepair/manuscript").asText());if(!needsSecondRepair || count>3)for(var contest:input.path("serverContests"))completed.withArray("contestResolutions").addObject().put("fingerprint",contest.at("/facts/fingerprint").asText()).put("occurred",false);completed.put("story","修复字段时不应替换这段原稿");}
   return reply(completed);
  }).when(model).chatForJson(anyList());
  doAnswer(call->{texts.incrementAndGet();return LLMResponse.raw(manuscript,0,0,0);}).when(model).chat(anyList());
  assertEquals("COMPLETED",operations.create(request("result-only-repair")).get("status"));assertEquals(manuscript,payload("result-only-repair").at("/sceneFrameFacts/resultRepair/manuscript").asText());
  assertEquals(needsSecondRepair?1:0,payload("result-only-repair").at("/sceneFrameFormatRepairs/0").asInt());
  int previousRoles=roles.get();var done=operations.recover(sid,pid,"result-only-repair",0);assertEquals("COMPLETED",done.get("status"),payload("result-only-repair").path("sceneIssue").asText());
  assertEquals(needsSecondRepair?4:3,plans.get());assertEquals(1,texts.get());assertEquals(previousRoles,roles.get());assertEquals(manuscript,payload("result-only-repair").path("story").asText());
  assertEquals(100,vitality.health(sid,self));assertEquals(100,vitality.health(sid,friend));
 }
}
