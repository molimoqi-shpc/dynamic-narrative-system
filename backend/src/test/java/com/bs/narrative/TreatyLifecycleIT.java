package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class TreatyLifecycleIT {
 @Autowired TreatyService treaties;@Autowired KnowledgeService knowledge;@Autowired JdbcTemplate jdbc;@Autowired GameActionService game;
 @MockBean LLMProvider model; final ObjectMapper json=new ObjectMapper();String sid,a,b,c,scene,pid;
 @BeforeEach void seed(){String tag=UUID.randomUUID().toString().substring(0,8);sid="pact_"+tag;a="pa_"+tag;b="pb_"+tag;c="pc_"+tag;scene="ps_"+tag;pid="pu_"+tag;
  for(String id:List.of(a,b,c))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静','[]')",id,sid,id.equals(a)?"行者":id.equals(b)?"渡者":"旁观者");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'渡口',JSON_ARRAY(?,?),'渡者与旁观者清醒。',0,0)",scene,sid,b,c);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,?,'unused',?,?,'[]')",pid,a,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,?,?,'PLAYING')",sid,pid,a,scene);jdbc.update("INSERT INTO game_runtime(session_id,story_seconds) VALUES(?,0)",sid);
  jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,is_allied,is_backstabbed) VALUES(?,?,?,55,false,false)",sid,a,b);
 }
 @AfterEach void noApiOrPsychologyChanges(){verifyNoInteractions(model);assertEquals(55,jdbc.queryForObject("SELECT trust_value FROM god_relationship WHERE session_id=?",Integer.class,sid));assertFalse(jdbc.queryForObject("SELECT is_backstabbed FROM god_relationship WHERE session_id=?",Boolean.class,sid));}
 String proof(String from,String to,String text,String kind,long at,List<String> heard)throws Exception{String ref="proof:"+UUID.randomUUID();var body=json.writeValueAsString(Map.of("recordType",kind,"actorId",from,"targetId",to,"text",text,"endSeconds",at));for(String owner:heard)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'实际交付',?,'TURN_EVENT',?,?,'渡口',0)",sid,owner,ref,body,from,scene);return ref;}
 String say(String from,String to,String text,long at)throws Exception{String ref=proof(from,to,text,"UTTERANCE",at,List.of(from,to));treaties.captureDelivered(sid,from,to,text,scene,at,ref);return ref;}
 JsonNode pact(String owner)throws Exception{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY' ORDER BY knowledge_id DESC LIMIT 1",String.class,sid,owner));}
 void active()throws Exception{say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,"我同意与你结盟。",5);assertEquals("ACTIVE",pact(a).path("status").asText());}
 void time(long seconds){jdbc.update("UPDATE game_runtime SET story_seconds=? WHERE session_id=?",seconds,sid);treaties.refresh(sid);}
 @Test void silenceDoesNotBindAndOnlyExplicitReciprocalConsentActivatesTheSameTerms()throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);assertEquals("PROPOSED",pact(a).path("status").asText());assertEquals(180,pact(a).path("durationSeconds").asInt());say(a,b,"我同意与你结盟。",2);assertEquals("PROPOSED",pact(a).path("status").asText());
  say(b,a,"我愿意与你结盟，期限一小时。",3);assertEquals("PROPOSED",pact(a).path("status").asText());String ref=say(b,a,"我同意与你结盟。",5);assertEquals("ACTIVE",pact(a).path("status").asText());assertEquals(185,pact(a).path("dueSeconds").asLong());assertEquals(2,pact(a).path("events").size());
  treaties.captureDelivered(sid,b,a,"我同意与你结盟。",scene,5,ref);assertEquals(2,pact(a).path("events").size());assertEquals(pact(a),pact(b));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY'",Integer.class,sid,c));assertFalse(knowledge.privateContext(sid,c).contains("盟约生效"));assertTrue(knowledge.privateContext(sid,b).contains("保留各自行动"));
 }
 @ParameterizedTest @ValueSource(strings={"我同意与你结盟？","如果你愿意，我同意与你结盟。","他说：我同意与你结盟。","“我同意与你结盟。”","我没有同意与你结盟。","我同意与你结盟，但你必须先把船给我。","我同意与你结盟。条件是替我报仇。","我同意与你结盟。我拒绝与你结盟。","好。"})
 void questionsQuotesConditionsAndConflictingRepliesNeverCountAsConsent(String reply)throws Exception{say(a,b,"我提议与你结盟。",1);say(b,a,reply,5);assertEquals("PROPOSED",pact(a).path("status").asText());}
 @ParameterizedTest @ValueSource(strings={"missing-recipient","wrong-target","wrong-time","unspoken"})
 void onlyTheNamedAudienceWithActualCompleteDeliveryCanBeBound(String mode)throws Exception{String speech="我提议与你结盟。";String ref=proof(a,b,speech,"UTTERANCE",1,mode.equals("missing-recipient")?List.of(a):List.of(a,b,c));treaties.captureDelivered(sid,a,mode.equals("wrong-target")?c:b,mode.equals("unspoken")?"我提议与你结盟，期限三分钟。":speech,scene,mode.equals("wrong-time")?2:1,ref);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY'",Integer.class,sid));}
 @ParameterizedTest @ValueSource(strings={"reject","withdraw"})
 void refusalAndWithdrawalCloseTheOfferWithoutCreatingHostility(String mode)throws Exception{say(a,b,"我提议与你结盟。",1);say(mode.equals("reject")?b:a,mode.equals("reject")?a:b,mode.equals("reject")?"我拒绝与你结盟。":"我撤回结盟提议。",5);assertEquals(mode.equals("reject")?"DECLINED":"WITHDRAWN",pact(a).path("status").asText());say(b,a,"我同意与你结盟。",6);assertNotEquals("ACTIVE",pact(a).path("status").asText());say(a,b,"我提议与你结盟。",7);assertEquals("PROPOSED",pact(a).path("status").asText());}
 @Test void durationStartsAtAcceptanceExpiresAtBoundaryAndNeverSilentlyRenews()throws Exception{active();time(184);assertEquals("ACTIVE",pact(a).path("status").asText());time(185);assertEquals("EXPIRED",pact(a).path("status").asText());time(300);assertEquals(3,pact(a).path("events").size());say(b,a,"我同意与你结盟。",301);assertEquals("EXPIRED",pact(a).path("status").asText());}
 @Test void indefinitePactSurvivesManyTurnsAndEitherPartyCanEndIt()throws Exception{say(a,b,"我提议与你结盟。",1);say(b,a,"我接受你的结盟提议。",5);time(90000);assertEquals("ACTIVE",pact(a).path("status").asText());assertFalse(pact(a).has("dueSeconds"));say(b,a,"我解除与你的盟约。",90001);assertEquals("ENDED",pact(a).path("status").asText());assertEquals(pact(a),pact(b));}
 @Test void actualPerceivedAttackBreaksPactButAHiddenIntentDoesNot()throws Exception{active();treaties.recordAggression(sid,a,b,10,"invented");assertEquals("ACTIVE",pact(a).path("status").asText());String hidden=proof(a,b,"实际开始交手","ATTEMPT_BEGIN",10,List.of(a));treaties.recordAggression(sid,a,b,10,hidden);assertEquals("ACTIVE",pact(a).path("status").asText());String actual=proof(a,b,"实际开始交手","ATTEMPT_BEGIN",11,List.of(a,b));treaties.recordAggression(sid,a,b,11,actual);assertEquals("BROKEN",pact(a).path("status").asText());assertEquals(pact(a),pact(b));assertEquals(3,pact(a).path("events").size());}
 @Test void attackAfterTheTermDoesNotBackdateABreach()throws Exception{active();String event=proof(a,b,"实际开始交手","ATTEMPT_BEGIN",185,List.of(a,b));treaties.recordAggression(sid,a,b,185,event);assertEquals("EXPIRED",pact(a).path("status").asText());}
 @ParameterizedTest @ValueSource(strings={"active","expired","ended","broken","declined","withdrawn","missing-recipient","different-copy","future","before-offer","changed-duration"})
 void historicalRepairUsesOnlyCompleteMatchingBranchDeliveriesAndNeverRenews(String mode)throws Exception{
  if(mode.equals("before-offer"))proof(b,a,"三分钟内我与你结盟，各自行事、互不交手。","UTTERANCE",0,List.of(a,b));
  say(a,b,"我提议与你结盟，期限三分钟。",1);
  if(mode.equals("withdrawn"))proof(a,b,"我撤回结盟提议。","UTTERANCE",3,List.of(a,b));
  if(mode.equals("declined"))proof(b,a,"我拒绝与你结盟。","UTTERANCE",3,List.of(a,b));
  String text=mode.equals("changed-duration")?"十秒内我与你结盟，各自行事、互不交手。":"是，我应你。三分钟内我与你结盟，各自行事、互不交手——我不动你，你也别来扰我晒药。";
  String ref=mode.equals("before-offer")?"":proof(b,a,text,"UTTERANCE",5,mode.equals("missing-recipient")?List.of(b):List.of(a,b));
  if(mode.equals("different-copy"))jdbc.update("UPDATE role_knowledge SET content=JSON_SET(content,'$.text','我拒绝与你结盟。') WHERE session_id=? AND god_id=? AND source_ref=?",sid,a,ref);
  if(mode.equals("ended"))proof(a,b,"我解除与你的盟约。","UTTERANCE",7,List.of(a,b));
  if(mode.equals("broken"))proof(b,a,"发起交手","ATTEMPT_BEGIN",7,List.of(a,b));
  // A later repeated acceptance is not a new 180-second term.
  if(Set.of("active","expired").contains(mode))proof(b,a,text,"UTTERANCE",9,List.of(a,b));
  time(mode.equals("future")?4:mode.equals("expired")?200:10);
  String expected=switch(mode){case "active"->"ACTIVE";case "expired"->"EXPIRED";case "ended"->"ENDED";case "broken"->"BROKEN";case "declined"->"DECLINED";case "withdrawn"->"WITHDRAWN";default->"PROPOSED";};
  var saved=pact(a);assertEquals(expected,saved.path("status").asText());assertEquals(saved,pact(b));
  if(Set.of("active","expired","ended","broken").contains(mode)){assertEquals(5,saved.path("acceptedSeconds").asLong());assertEquals(185,saved.path("dueSeconds").asLong());assertEquals(ref,saved.path("events").get(1).path("eventRef").asText());}
  int count=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid);treaties.refresh(sid);assertEquals(saved,pact(a));assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
 }
 @ParameterizedTest @ValueSource(strings={"我同意与你结盟。其实不结盟。","我同意与你结盟。这不是接受结盟。","三分钟内我与你结盟。十秒内就结束。"})
 void acceptanceCannotOverrideNaturalRetractionOrAnotherTimeWindow(String reply)throws Exception{say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,reply,5);assertEquals("PROPOSED",pact(a).path("status").asText());}
 @ParameterizedTest @ValueSource(strings={"1000180秒内有效。","一百三分钟内有效。","9999999999999999999999180秒内有效。","1.180秒内有效。"})
 void explicitConsentCannotReadOnlyTheValidSuffixOfAnInvalidDuration(String tail)throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,"我同意与你结盟。"+tail,5);assertEquals("PROPOSED",pact(a).path("status").asText());
 }
 @ParameterizedTest @ValueSource(strings={"其实我没有同意。","我反悔了。","刚才的话作废。","期限1000180秒。"})
 void contextualYesDoesNotBypassRetractionOrInvalidTermValidation(String tail)throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);String question="你是否愿意按原提议，在三分钟内与我结盟？";String reference=proof(a,b,question,"UTTERANCE",3,List.of(a,b));String speech="是，我应你。"+tail;String event=proof(b,a,speech,"UTTERANCE",5,List.of(a,b));
  treaties.captureDelivered(sid,b,a,speech,scene,5,event,reference);assertEquals("PROPOSED",pact(a).path("status").asText());
 }
 @Test void sceneReadRepairsDerivedPactWithoutNewDeliveryOrTimeAdvance()throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);String event=proof(b,a,"三分钟内我与你结盟，各自行事、互不交手。","UTTERANCE",5,List.of(a,b));jdbc.update("UPDATE game_runtime SET story_seconds=10 WHERE session_id=?",sid);
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TURN_EVENT'",Integer.class,sid);
  var view=game.describeScene(pid,sid);assertTrue(view.get("knowledge").toString().contains("盟约生效"));assertEquals(10L,view.get("storySeconds"));assertEquals(185,pact(a).path("dueSeconds").asLong());assertEquals(event,pact(a).path("events").get(1).path("eventRef").asText());
  assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TURN_EVENT'",Integer.class,sid));
 }
 @Test void notebookShowsReadableTermsWithoutExposingRawConsentIdentifiers()throws Exception{active();var view=knowledge.list(sid,a).stream().filter(r->"TREATY".equals(r.get("sourceType"))).findFirst().orElseThrow();assertEquals("盟约记录 · 双方表态",view.get("sourceLabel"));assertTrue(view.get("content").toString().contains("盟约生效"));assertFalse(view.get("content").toString().contains("eventRef"));assertEquals(false,view.get("canVerify"));}
 @ParameterizedTest @ValueSource(strings={"我接受你提出的结盟提议。","你的结盟提议，我同意。","我同意这份结盟提议。","我同意按原约定与你结盟。","我同意与你结盟，期限三分钟。"})
 void naturalExplicitAcceptanceBindsOnlyTheOriginalTerms(String reply)throws Exception{say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,reply,5);assertEquals("ACTIVE",pact(a).path("status").asText());assertEquals(185,pact(a).path("dueSeconds").asLong());}
 @ParameterizedTest @ValueSource(strings={"结盟就不必了。","我不愿与你结盟。","我不想与你结盟。"})
 void explicitNaturalRefusalDoesNotStayPending(String reply)throws Exception{say(a,b,"我提议与你结盟。",1);say(b,a,reply,5);assertEquals("DECLINED",pact(a).path("status").asText());}
 @ParameterizedTest @ValueSource(strings={"我同意与你结盟。结盟就不必了。","我同意与你结盟。我不愿与你结盟。","我同意与你结盟。期限改为十秒。","我同意与你结盟，期限十秒。","我同意与你结盟。我们的盟约只是说说，不算数。","我同意与你结盟，期限三分钟。期限改为十秒。"})
 void contradictoryConsentOrChangedTermNeverSilentlyBindsOriginalTerms(String reply)throws Exception{say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,reply,5);assertEquals("PROPOSED",pact(a).path("status").asText());assertEquals(1,pact(a).path("events").size());}
 @Test void acceptingOnlyNonAggressionDoesNotInventAnAlliance()throws Exception{say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,"互不交手，各自行事——这个我可以应。三分钟便三分钟，我不主动动你，你也别来扰我翻晒这些药。",5);assertEquals("PROPOSED",pact(a).path("status").asText());}
 @ParameterizedTest @ValueSource(strings={"valid","unrelated","wrong-reference","changed-time","retracted","missing-delivery","multiple-questions"})
 void shortAssentNeedsItsFrozenActuallyReceivedUnambiguousOriginalAllianceQuestion(String mode)throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);String question=mode.equals("unrelated")?"你是否愿意给我一片叶子？":mode.equals("multiple-questions")?"你是否愿意按原提议，三分钟内与我结盟？你是否愿意给我叶子？":"刚才你说可以互不交手。我想确认：你是否愿意按原提议，在三分钟内与我结盟、各自行事？接受、拒绝或另有打算都请直接告诉我。";
  String reference=proof(a,b,question,"UTTERANCE",3,mode.equals("missing-delivery")?List.of(a):List.of(a,b));String reply=mode.equals("changed-time")?"是，我应你。期限改为十秒。":mode.equals("retracted")?"是，我应你。只是互不交手，不结盟。":"是，我应你。三分钟内互不交手、各自行事，我不主动动你，你也别来扰我晒药。";
  String event=proof(b,a,reply,"UTTERANCE",5,List.of(a,b));treaties.captureDelivered(sid,b,a,reply,scene,5,event,mode.equals("wrong-reference")?"missing":reference);
  assertEquals(mode.equals("valid")?"ACTIVE":"PROPOSED",pact(a).path("status").asText());if(mode.equals("valid")){assertEquals(185,pact(a).path("dueSeconds").asLong());assertEquals(reference,pact(a).path("events").get(1).path("replyToEventRef").asText());}
 }
 @ParameterizedTest @ValueSource(strings={"valid","changed-term","no-offer"})
 void explicitRealAllianceDeclarationNeedsMatchingPendingMutualTerms(String mode)throws Exception{
  if(!mode.equals("no-offer"))say(a,b,"我提议与你结盟，期限三分钟。",1);
  say(b,a,mode.equals("changed-term")?"十秒内，我与你结盟，各走各路、互不交手。":"好，我说清楚——三分钟内，我与你结盟，各走各路、互不交手。我不主动动你，你也别来扰我翻晒这些药。这话说到这个份上，够准了吧。",5);
  if(mode.equals("no-offer"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY'",Integer.class,sid));else assertEquals(mode.equals("valid")?"ACTIVE":"PROPOSED",pact(a).path("status").asText());
 }
 @ParameterizedTest @ValueSource(strings={
  "是，我应你。三分钟内我与你结盟，各自行事、互不交手——我不动你，你也别来扰我翻晒这些药。这话说到这儿，不必再问了。",
  "三分钟内，我与你结盟，各自行事、互不交手。",
  "三分钟内我和你结盟，各走各路、互不交手。",
  "三分钟内，我与你结盟，各自行事，互不交手——我不主动动你，你也别来扰我晒药。"
 })
 void deliveredDeclarationDoesNotDependOnOptionalCommaOrRoutineNonAggressionTail(String speech)throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);String ref=say(b,a,speech,5);assertEquals("ACTIVE",pact(a).path("status").asText());assertEquals(185,pact(a).path("dueSeconds").asLong());assertEquals(ref,pact(a).path("events").get(1).path("eventRef").asText());assertEquals(pact(a),pact(b));
 }
 @ParameterizedTest @ValueSource(strings={
  "三分钟内我与你结盟，各自行事、互不交手？",
  "他说：三分钟内我与你结盟，各自行事、互不交手。",
  "三分钟内我与你结盟，各自行事、互不交手——你先交出铜铃。",
  "三分钟内我与你结盟，各自行事、互不交手——如果你替我报仇。",
  "十秒内我与你结盟，各自行事、互不交手。",
  "三分钟内我与你结盟，各自行事、互不交手。我拒绝与你结盟。"
 })
 void declarationVariationsStillRejectQuestionsHearsayNewTermsAndContradictions(String speech)throws Exception{
  say(a,b,"我提议与你结盟，期限三分钟。",1);say(b,a,speech,5);assertEquals("PROPOSED",pact(a).path("status").asText());
 }
}
