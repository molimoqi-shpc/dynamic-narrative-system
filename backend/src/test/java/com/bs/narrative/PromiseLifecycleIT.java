package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
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
class PromiseLifecycleIT {
 @Autowired com.bs.narrative.repository.GodProfileRepository profiles;@Autowired ItemStateService items;@Autowired PromiseService promises;@Autowired KnowledgeService knowledge;@Autowired GameActionService actions;
 @Autowired NpcAgentService actors;@Autowired SessionStateService state;@Autowired JdbcTemplate jdbc;@Autowired EntityManager em;
 @MockBean LLMProvider model;
 static final ObjectMapper J=new ObjectMapper();
 String sid="promise_it",pid="promise_user",role="promise_player",npc="promise_npc",scene="promise_scene";
 @BeforeEach void seed(){
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,'你','MORTAL','平静',10,10,10,10,20,75,'[\"水囊\"]'),(?,?,'渡者','MORTAL','平静',10,10,10,10,20,75,'[\"通行印\"]')",role,sid,npc,sid);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'渡口',?,'渡者守着旧船。旧桥在东面。',0,0),('promise_bridge',?,'旧桥','[]','旧桥已经开放。',1,0)",scene,sid,"[\""+npc+"\"]",sid);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'promise_test','unused',?,?,'[\"水囊\"]')",pid,role,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,status,player_god_id,current_scene_id) VALUES(?,?,'PLAYING',?,?)",sid,pid,role,scene);
  jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,is_allied,is_backstabbed) VALUES(?,?,?,70,false,false)",sid,npc,role);
  state.initialize(sid,pid);
  when(model.chatForJson(anyList())).thenReturn(reply(J.createObjectNode().set("visible",J.createObjectNode().set("lines",J.createArrayNode()))));
  when(model.chatSubForJson(anyList())).thenReturn(reply(J.createObjectNode().put("behavior","IGNORE")));
 }
 LLMResponse reply(JsonNode n){return new LLMResponse(n.toString(),n.toString(),0,0,0,0);}
 ObjectNode spec(String to,String quote,String kind,String value,int delay){return J.createObjectNode().put("toGodId",to).put("quote",quote).put("kind",kind).put("value",value).put("afterTicks",delay);}
 void clock(int t){jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,?) ON DUPLICATE KEY UPDATE tick=VALUES(tick)",sid,t);}
 boolean capture(String from,String to,String quote,String kind,String value,int delay){return promises.capture(sid,from,to,quote,spec(to,quote,kind,value,delay),scene);}
 String status(String owner){String content=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE' ORDER BY knowledge_id DESC LIMIT 1",String.class,sid,owner);try{return J.readTree(content).path("status").asText();}catch(Exception e){throw new RuntimeException(e);}}
 void unchangedRelation(){assertEquals(70,jdbc.queryForObject("SELECT trust_value FROM god_relationship WHERE session_id=?",Integer.class,sid));assertFalse(jdbc.queryForObject("SELECT is_backstabbed FROM god_relationship WHERE session_id=?",Boolean.class,sid));}
 void talk(String speech,JsonNode npcReply){
  var p=J.createObjectNode().put("intent","TALK").put("targetGodId",npc).put("confidence",1.0).put("spokenText",speech);
  when(model.chatForJson(anyList())).thenReturn(reply(p));doReturn(reply(npcReply)).when(model).chatSubForJson(anyList());
  actions.processActionWithDb(new ActionRequest(pid,scene,"我说：“"+speech+"”",null),sid);
 }
 @Test void playerActuallySpokenPromiseIsRegisteredButPrivatePlanIsNot(){
  String quote="我承诺三刻内把水囊送给你。";
  var p=J.createObjectNode().put("intent","TALK").put("targetGodId",npc).put("confidence",1.0).put("spokenText",quote).set("promise",spec(npc,quote,"GIVE_ITEM","水囊",3));
  when(model.chatForJson(anyList())).thenReturn(reply(p));actions.processActionWithDb(new ActionRequest(pid,scene,"我向渡者承诺：“"+quote+"”心里另有打算。",null),sid);
  assertEquals("OPEN",status(npc));assertTrue(knowledge.privateContext(sid,npc).contains(quote));assertFalse(knowledge.privateContext(sid,npc).contains("另有打算"));
  state.restore(sid,0);when(model.chatForJson(anyList())).thenReturn(reply(p));
  actions.processActionWithDb(new ActionRequest(pid,scene,"我心里想：“"+quote+"”但没有说出口。",null),sid);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void overdueIsUncertainAndLateRealGiftDoesNotEraseDelay(){
  assertTrue(capture(role,npc,"我保证二刻内把水囊赠予你。","GIVE_ITEM","水囊",2));clock(3);promises.refresh(sid,null,true);
  assertEquals("OVERDUE",status(npc));unchangedRelation();
  promises.recordDelivery(sid,new OfferService.Offer("GIVE",role,npc,"水囊",""),"COMPLETED");assertEquals("OVERDUE",status(npc));
  var p=J.createObjectNode().put("intent","GIVE").put("targetGodId",npc).put("itemId","水囊").put("confidence",1.0);
  when(model.chatForJson(anyList())).thenReturn(reply(p));doReturn(reply(J.createObjectNode().put("behavior","RESPOND").put("accept_offer",true))).when(model).chatSubForJson(anyList());
  actions.processActionWithDb(new ActionRequest(pid,scene,"我把水囊赠予渡者，由他决定。",null),sid);assertEquals("FULFILLED_LATE",status(npc));unchangedRelation();
 }
 @Test void boundaryAttackIsViolationAndDoesNotChooseTheResponse(){
  assertTrue(capture(role,npc,"我答应三刻内不攻击你。","NON_AGGRESSION","",3));clock(3);promises.refresh(sid,npc,false);assertEquals("OPEN",status(npc));
  promises.recordAggression(sid,role,npc);promises.refresh(sid,null,true);assertEquals("VIOLATED",status(npc));unchangedRelation();
 }
 @Test void peacefulTermEndsWithoutGuaranteeingFutureLoyalty(){
  assertTrue(capture(role,npc,"我答应三刻内不攻击你。","NON_AGGRESSION","",3));clock(3);promises.refresh(sid,null,true);assertEquals("FULFILLED",status(npc));
  clock(4);promises.recordAggression(sid,role,npc);assertEquals("FULFILLED",status(npc));unchangedRelation();
 }
 @Test void sameViolationCanLeadToDifferentApiResponsesAndOldBondRemainsAvailable(){
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'social:past','过去交往','玩家过去曾赠予你水囊；这次实际相助不等于永远可信。','SOCIAL',?,?,'渡口',0)",sid,npc,role,scene);
  assertTrue(capture(role,npc,"我答应三刻内不攻击你。","NON_AGGRESSION","",3));promises.recordAggression(sid,role,npc);
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String c=m.get(1).get("content");assertTrue(c.contains("过去曾赠予你水囊"));assertTrue(c.contains("不预设敌对"));return reply(J.createObjectNode().put("behavior","RESPOND").put("speech","我记得先前的相助，先听你解释。").put("inner_thought","难以全信，但不愿立刻为敌。").put("emotional_state","犹疑与克制"));});
  jdbc.update("INSERT INTO npc_decision_log(session_id,god_id,scene_id,player_input,behavior,speech,inner_thought,is_lying,used_fallback) VALUES(?,?,?,'','RESPOND','先听解释','我之前曾动摇，并不想立即为敌。',false,false)",sid,npc,scene);
  assertTrue(knowledge.privateContext(sid,npc).contains("我之前曾动摇"));assertFalse(knowledge.privateContext(sid,role).contains("我之前曾动摇"));
  assertEquals("RESPOND",actors.decideNpcBehavior(sid,npc,"现场发生过攻击。","渡口",List.of(role,npc)).behavior());
  em.flush();assertEquals("犹疑与克制",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,npc));
  doReturn(reply(J.createObjectNode().put("behavior","LEAVE").put("speech","我先离开，留些时间想清楚。"))).when(model).chatSubForJson(anyList());
  assertEquals("LEAVE",actors.decideNpcBehavior(sid,npc,"现场发生过攻击。","渡口",List.of(role,npc)).behavior());unchangedRelation();
 }
 @Test void npcPromiseCanActuallyBeFulfilledByVoluntaryGiftAndRollbackRestoresIt(){
  assertTrue(capture(npc,role,"我答应三刻内把通行印送给你。","GIVE_ITEM","通行印",3));
  talk("我愿收下通行印。",J.createObjectNode().put("behavior","GIVE").put("target_god_id",role).put("gift_item","通行印").put("speech","若你愿意，我便赠予此印。"));
  assertEquals("FULFILLED",status(role));assertTrue(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("通行印"));
  state.restore(sid,0);assertFalse(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("通行印"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void npcCannotForceGiftOrCreateMissingItems(){
  assertTrue(capture(npc,role,"我答应三刻内把尚未获得的药草赠予你。","GIVE_ITEM","药草",3));
  assertFalse(jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,npc).contains("药草"));
  assertTrue(capture(npc,role,"我答应三刻内把通行印送给你。","GIVE_ITEM","通行印",3));
  talk("你想聊些什么？",J.createObjectNode().put("behavior","GIVE").put("target_god_id",role).put("gift_item","通行印"));
  assertEquals("OPEN",status(role));assertFalse(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("通行印"));
  talk("你记得谁曾给我通行印吗？",J.createObjectNode().put("behavior","GIVE").put("target_god_id",role).put("gift_item","通行印"));
  assertFalse(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("通行印"));
  talk("我愿收下无穷神器。",J.createObjectNode().put("behavior","GIVE").put("target_god_id",role).put("gift_item","无穷神器"));
  assertFalse(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("无穷神器"));
 }
 @Test void metadataCannotInventSpeechDeadlineAudienceOrResetExistingPromise(){
  String q="我答应三刻内不攻击你。";
  assertFalse(promises.capture(sid,role,npc,"我正在看船。",spec(npc,q,"NON_AGGRESSION","",3),scene));
  assertFalse(promises.capture(sid,role,"other",q,spec(npc,q,"NON_AGGRESSION","",3),scene));
  assertFalse(capture(role,npc,q,"NON_AGGRESSION","",4));assertTrue(capture(role,npc,q,"NON_AGGRESSION","",3));
  clock(1);assertFalse(capture(role,npc,q,"NON_AGGRESSION","",3));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void meetingNeedsBothPartiesAndComplexVowsRemainUnverified(){
  assertTrue(capture(role,npc,"我保证三刻内与你在旧桥会面。","MEET","旧桥",3));
  jdbc.update("UPDATE player_profile SET current_scene_id='promise_bridge' WHERE player_id=?",pid);promises.refresh(sid,null,true);assertEquals("OPEN",status(npc));
  jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE scene_id=?",scene);jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id='promise_bridge'","[\""+npc+"\"]");promises.refresh(sid,null,true);assertEquals("FULFILLED",status(npc));
  jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE player_id=?",scene,pid);jdbc.update("UPDATE game_scene SET present_gods=? WHERE scene_id=?","[\""+npc+"\"]",scene);
  assertTrue(capture(role,npc,"我承诺以后条件允许时帮助你。","UNVERIFIED","",0));clock(20);promises.refresh(sid,null,true);assertEquals("UNVERIFIED",status(npc));
 }
 @Test void npcSpokenPromiseIsCapturedAndThirdPartiesDoNotGetPrivateProgress(){
  String q="我答应三刻内不攻击你。";var answer=J.createObjectNode().put("behavior","RESPOND").put("target_god_id",role).put("speech",q);answer.set("promise",spec(role,q,"NON_AGGRESSION","",3));
  talk("你愿意谈谈吗？",answer);assertEquals("OPEN",status(role));assertEquals("OPEN",status(npc));
  jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui) VALUES('promise_third',?,'旁观者','MORTAL','平静',75)",sid);
  assertFalse(knowledge.privateContext(sid,"promise_third").contains(q));
  assertTrue(knowledge.list(sid,role).stream().filter(k->k.get("sourceType").equals("PROMISE")).allMatch(k->k.get("content").toString().contains("待履行")));
 }
 @Test void actualPlayerAttackIsVisibleAsViolationBeforeNpcChoosesItsReaction(){
  assertTrue(capture(role,npc,"我答应三刻内不攻击你。","NON_AGGRESSION","",3));
  var p=J.createObjectNode().put("intent","ATTACK").put("targetGodId",npc).put("attackStatKey","dou").put("confidence",1.0);
  when(model.chatForJson(anyList())).thenReturn(reply(p));
  doAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.get(1).get("content").contains("言行违约·不预设敌对"));return reply(J.createObjectNode().put("behavior","RESPOND").put("speech","先听你解释这次出手。"));}).when(model).chatSubForJson(anyList());
  var result=actions.processActionWithDb(new ActionRequest(pid,scene,"我向渡者发起攻击。",null),sid);
  assertEquals("VIOLATED",status(npc));assertTrue(result.lines().stream().anyMatch(l->l.speech().equals("先听你解释这次出手。")));
 }
 @Test void npcMayChooseToBreakItsPromiseAndRealAttackIsRecorded(){
  assertTrue(capture(npc,role,"我答应三刻内不攻击你。","NON_AGGRESSION","",3));
  when(model.chatForJson(anyList())).thenReturn(reply(J.createObjectNode().put("intent","OBSERVE").put("confidence",1.0)));
  doReturn(reply(J.createObjectNode().put("behavior","ATTACK").put("target_god_id",role).put("speech","我改变了主意。"))).when(model).chatSubForJson(anyList());
  actions.processActionWithDb(new ActionRequest(pid,scene,"我观察当前场景。",null),sid);assertEquals("VIOLATED",status(role));
 }


 @Test void zeroGuiDoesNotPreventAnActuallySpokenPromise(){
  jdbc.update("UPDATE god_profile SET base_gui=0 WHERE session_id=?",sid);em.clear();assertTrue(capture(role,npc,"我承诺一刻后赠送水囊给你","GIVE_ITEM","水囊",1));assertEquals("OPEN",status(role));
 }
 @Test void exactGiftFulfillsEvenWhenTheGiverRetainsAnotherItemWithTheSameName(){
  assertTrue(capture(role,npc,"我承诺一刻后赠送水囊给你","GIVE_ITEM","水囊",1));jdbc.update("UPDATE god_profile SET inventory='[\"水囊\",\"水囊\"]' WHERE god_id=?",role);em.clear();
  var giver=profiles.findById(role).orElseThrow();var receiver=profiles.findById(npc).orElseThrow();String ref=items.owned(sid,giver).get(0).ref();items.transferExact(sid,giver,receiver,ref,"GIFT");profiles.saveAndFlush(giver);profiles.saveAndFlush(receiver);
  promises.recordDelivery(sid,new OfferService.Offer("GIVE",role,npc,"水囊",""),"COMPLETED",ref);assertEquals("FULFILLED",status(role));assertTrue(giver.getInventory().contains("水囊"));assertTrue(receiver.getInventory().contains("水囊"));
 }
 @Test void wrongInstanceAndLoanCannotPretendToFulfillAGift(){
  assertTrue(capture(role,npc,"我承诺一刻后赠送水囊给你","GIVE_ITEM","水囊",1));var giver=profiles.findById(role).orElseThrow();var receiver=profiles.findById(npc).orElseThrow();String ref=items.owned(sid,giver).get(0).ref();items.transferExact(sid,giver,receiver,ref,"LEND");profiles.saveAndFlush(giver);profiles.saveAndFlush(receiver);
  var offer=new OfferService.Offer("GIVE",role,npc,"水囊","");promises.recordDelivery(sid,offer,"COMPLETED","fake-instance");assertEquals("OPEN",status(role));promises.recordDelivery(sid,offer,"COMPLETED",ref);assertEquals("OPEN",status(role));
 }

 void secondClock(long value){jdbc.update("INSERT INTO game_runtime(session_id,story_seconds) VALUES(?,?) ON DUPLICATE KEY UPDATE story_seconds=VALUES(story_seconds)",sid,value);}
 JsonNode secondPromise(){try{return J.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE' ORDER BY knowledge_id DESC LIMIT 1",String.class,sid,role));}catch(Exception e){throw new RuntimeException(e);}}
 void delivered(String speech,long at){promises.captureDelivered(sid,role,npc,speech,scene,at);}
 @Test void storySecondsExpireWhileLegacyWorldTickStaysStill(){
  delivered("我承诺30秒内把水囊送给你。",10);secondClock(40);promises.refresh(sid,null,true);assertEquals("OPEN",status(npc));secondClock(41);promises.refresh(sid,null,true);assertEquals("OVERDUE",status(npc));assertEquals(40,secondPromise().path("dueSeconds").asLong());unchangedRelation();
 }
 @Test void actualLateDeliveryUsesItsEventTimeRatherThanOldRuntimeClock(){
  delivered("我承诺三十秒内把水囊送给你。",10);secondClock(10);var giver=profiles.findById(role).orElseThrow();var receiver=profiles.findById(npc).orElseThrow();String ref=items.owned(sid,giver).get(0).ref();items.transferExact(sid,giver,receiver,ref,"GIFT");profiles.flush();
  promises.recordDelivery(sid,new OfferService.Offer("GIVE",role,npc,"水囊",""),"COMPLETED",ref,41);assertEquals("FULFILLED_LATE",status(npc));unchangedRelation();
 }
 @Test void attackAtExactSecondDeadlineStillViolatesDespiteAnEarlierFinishAtThatTime(){
  delivered("我答应十秒内不攻击你。",10);secondClock(20);promises.refresh(sid,null,true);assertEquals("OPEN",status(npc));promises.recordAggression(sid,role,npc,20);assertEquals("VIOLATED",status(npc));unchangedRelation();
 }
 @Test void attackAfterDeadlineDoesNotRewriteFulfilledPeace(){
  delivered("我保证两分钟内不会攻击你。",10);secondClock(131);promises.refresh(sid,null,true);assertEquals("FULFILLED",status(npc));promises.recordAggression(sid,role,npc,131);assertEquals("FULFILLED",status(npc));
 }
 @Test void questionRetrospectiveAndQuotedSpeechAreNotNewPromises(){
  for(String speech:List.of("我承诺30秒内把水囊送给你？","我答应过30秒内把水囊送给你。","我答应不了这件事。","他说：我承诺30秒内把水囊送给你。","我心里想：我承诺30秒内把水囊送给你。"))delivered(speech,10);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void complexAndConditionalPromisesRemainUnverifiedInsteadOfInventingTerms(){
  delivered("我承诺如果找到水囊就送给你。",10);assertEquals("UNVERIFIED",status(npc));assertEquals(0,secondPromise().path("dueSeconds").asLong());secondClock(999);promises.refresh(sid,null,true);assertEquals("UNVERIFIED",status(npc));unchangedRelation();
 }
 @Test void repeatedDeliveredPromiseDoesNotResetItsDeadline(){
  delivered("我承诺十秒内把水囊送给你。",10);delivered("我承诺十秒内把水囊送给你。",15);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));assertEquals(20,secondPromise().path("dueSeconds").asLong());
 }
 @Test void secondPromiseIsRemovedWhenRewindingToBeforeItWasSaid(){
  delivered("我承诺一分钟内把水囊送给你。",10);assertEquals("OPEN",status(npc));state.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void actualMeetingFulfillsSecondDeadlineAndCopiesStayConsistent(){
  delivered("我承诺一分钟内在旧桥与你见面。",10);assertEquals("OPEN",status(npc));secondClock(40);jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE session_id=? AND scene_id=?",sid,scene);jdbc.update("UPDATE game_scene SET present_gods=? WHERE session_id=? AND scene_id='promise_bridge'","[\""+npc+"\"]",sid);jdbc.update("UPDATE player_profile SET current_scene_id='promise_bridge' WHERE player_id=?",pid);promises.refresh(sid,null,true);assertEquals("FULFILLED",status(npc));assertEquals(status(role),status(npc));
 }
 @Test void sharingTheMeetingLocationWithAnUnconsciousPersonIsNotFulfillment(){
  delivered("我承诺一分钟内在旧桥和你见面。",10);secondClock(40);jdbc.update("UPDATE game_scene SET present_gods='[]' WHERE session_id=? AND scene_id=?",sid,scene);jdbc.update("UPDATE game_scene SET present_gods=? WHERE session_id=? AND scene_id='promise_bridge'","[\""+npc+"\"]",sid);jdbc.update("UPDATE player_profile SET current_scene_id='promise_bridge' WHERE player_id=?",pid);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','昏迷','{\"awake\":false}','BODY_STATE','','promise_bridge','旧桥',0)",sid,npc);em.clear();promises.refresh(sid,null,true);assertEquals("OPEN",status(npc));
  jdbc.update("UPDATE role_knowledge SET content='{\"awake\":true}' WHERE session_id=? AND god_id=? AND source_ref='body:v2'",sid,npc);promises.refresh(sid,null,true);assertEquals("FULFILLED",status(npc));
 }
 @Test void adjacentExplicitPromisesAreBothCapturedWithoutASpacer(){
  delivered("我承诺30秒内把水囊送给你。我答应一分钟内不攻击你。",10);assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",Integer.class,sid));
 }
 @Test void deliveryBeforeThePromiseWasSpokenCannotRetroactivelyFulfillIt(){
  delivered("我承诺三十秒内把水囊送给你。",10);var giver=profiles.findById(role).orElseThrow();var receiver=profiles.findById(npc).orElseThrow();String ref=items.owned(sid,giver).get(0).ref();items.transferExact(sid,giver,receiver,ref,"GIFT");profiles.flush();promises.recordDelivery(sid,new OfferService.Offer("GIVE",role,npc,"水囊",""),"COMPLETED",ref,9);assertEquals("OPEN",status(npc));
 }
 @Test void aggressionBeforeThePromiseWasSpokenCannotBecomeANewViolation(){
  delivered("我答应十秒内不攻击你。",10);promises.recordAggression(sid,role,npc,9);assertEquals("OPEN",status(npc));
 }
}
