package com.bs.narrative;
import com.bs.narrative.service.*;import com.bs.narrative.llm.*;import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.*;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.mock.mockito.MockBean;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;
import java.util.*;import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"}) @Transactional
class RoleContinuityIT {
 @Autowired NpcAgentService agents;@Autowired RoleContinuityService roles;@Autowired KnowledgeService knowledge;@Autowired JdbcTemplate jdbc;@MockBean LLMProvider model;
 ObjectMapper json=new ObjectMapper();String sid="role_continuity_it",a="continuity_worker_it",b="continuity_watch_it";
 @BeforeEach void seed(){for(var id:List.of(a,b))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',10,10,10,10,20,75,'[]')",id,sid,id.equals(a)?"木匠":"看守");jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,2)",sid);}
 JsonNode profile(String duty) throws Exception{return json.valueToTree(Map.of("self_profile",Map.of("duty",duty,"method","检查卡槽并尝试清除已看见的障碍","unknown","不知他人隐情","basis","木匠正在检查卡闸")));}
 JsonNode exchange(String status,String basis,boolean repeated) throws Exception{return json.valueToTree(Map.of("behavior","RESPOND","target_god_id",b,"exchange_update",Map.of("partner_id",b,"topic","卡闸原因","status",status,"detail","看守已经回答断木卡槽；是否可信仍需亲自检查","basis",basis,"repeated_question",repeated)));}
 @Test void selfUnderstandingPersistsOnceAndIsNotAnotherRolesKnowledge() throws Exception {
  roles.remember(sid,a,profile("处理卡闸"),"木匠正在检查卡闸");roles.remember(sid,a,profile("完全不同的新身份"),"木匠正在检查卡闸");
  assertTrue(roles.context(sid,a).contains("处理卡闸"));assertFalse(roles.context(sid,a).contains("完全不同的新身份"));assertFalse(roles.context(sid,b).contains("处理卡闸"));assertTrue(knowledge.list(sid,a).isEmpty());assertFalse(knowledge.privateContext(sid,a).contains("SELF_PROFILE"));
 }
 @Test void unsupportedKnowledgeAndForeignPartnerAreNotSaved() throws Exception {
  roles.remember(sid,a,profile("处理卡闸"),"不包含依据");roles.remember(sid,a,exchange("ANSWERED","断木卡槽",true),"断木卡槽 foreign_person");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
 }
 @Test void answeredQuestionIsRetainedAndRepeatOnlyRemovesNewDialogueNode() throws Exception {
  proof("断木卡槽");String grounding="角色 "+b+" 已经说过：断木卡槽";assertFalse(roles.remember(sid,a,exchange("ANSWERED","断木卡槽",false),grounding));assertTrue(roles.remember(sid,a,exchange("ANSWERED","断木卡槽",true),grounding));
  assertTrue(roles.context(sid,a).contains("ANSWERED"));assertTrue(roles.context(sid,a).contains("\"repeats\":1"));assertEquals(75,jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,a));
 }
 @Test void refusalIsAnInterpretationAndDoesNotForceCombatOrRelationship() throws Exception {
  proof("我不回答");roles.remember(sid,a,exchange("REFUSED","我不回答",false),b+"：我不回答");assertTrue(roles.context(sid,a).contains("REFUSED"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,a));
 }

 @Test void internalContinuityCannotBeSharedEvenWithItsKnowledgeId() throws Exception {
  roles.remember(sid,a,profile("处理卡闸"),"木匠正在检查卡闸");long id=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=?",Long.class,sid,a);
  var sender=new com.bs.narrative.domain.GodProfile();sender.setGodId(a);sender.setSessionId(sid);sender.setBaseGui(75);var recipient=new com.bs.narrative.domain.GodProfile();recipient.setGodId(b);recipient.setSessionId(sid);recipient.setBaseGui(75);var scene=new com.bs.narrative.domain.GameScene();scene.setPresentGods(List.of(a,b));
  assertTrue(knowledge.share(sid,a,b,id,"把角色私有连续认知告诉对方",scene,Map.of(a,sender,b,recipient),2).contains("不能"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=?",Integer.class,sid,b));
 }

 @Test void actualAgentPersistsProfileCitingExactlyWhatItWasShown() throws Exception {
  String payload=json.writeValueAsString(Map.of("behavior","IGNORE","self_profile",Map.of("duty","处理卡闸","method","检查卡槽再决定怎样清理","unknown","不知他人隐情","basis","姓名：木匠")));
  org.mockito.Mockito.when(model.chatSubForJson(org.mockito.ArgumentMatchers.anyList())).thenReturn(new LLMResponse(payload,payload,0,0,0,0));
  var decision=agents.decideNpcBehavior(sid,a,"没有新增公开玩家行为","木匠正在检查卡闸",List.of(a,b));assertFalse(decision.usedFallback());assertTrue(roles.context(sid,a).contains("处理卡闸"));assertFalse(roles.context(sid,a).contains("自身认知尚未建立"));
 }

 @Test void executedAttemptIsPrivateSceneScopedAndCannotBeShared() {
  roles.recordAttempt(sid,a,"own_scene","用维修杆探过裂缝",Map.of("activity","尚未检查"),Map.of("awake",true,"activity","检查结束，裂缝深两寸","percepts",List.of("杆尖受到硬物阻挡")));
  assertTrue(roles.attemptsContext(sid,a,"own_scene").contains("用维修杆探过裂缝"));assertTrue(roles.attemptsContext(sid,a,"own_scene").contains("检查结束"));
  assertEquals("",roles.attemptsContext(sid,b,"own_scene"));assertEquals("",roles.attemptsContext(sid,a,"foreign_scene"));
  assertTrue(knowledge.list(sid,a).isEmpty());assertFalse(knowledge.privateContext(sid,a).contains("维修杆"));
  long id=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=?",Long.class,sid,a);
  var scene=new com.bs.narrative.domain.GameScene();scene.setPresentGods(List.of(a,b));
  var sender=new com.bs.narrative.domain.GodProfile();sender.setGodId(a);sender.setSessionId(sid);sender.setBaseGui(75);
  var recipient=new com.bs.narrative.domain.GodProfile();recipient.setGodId(b);recipient.setSessionId(sid);recipient.setBaseGui(75);
  assertTrue(knowledge.share(sid,a,b,id,"告诉对方",scene,Map.of(a,sender,b,recipient),2).contains("不能"));
 }
 @Test void unconsciousActorDoesNotAcquireObservationAndNewestResultKeepsItsBudget() {
  roles.recordAttempt(sid,a,"own_scene","尝试苏醒",Map.of(),Map.of("awake",false,"activity","他人秘密"));
  assertEquals("",roles.attemptsContext(sid,a,"own_scene"));
  for(int i=2;i<=5;i++){
   jdbc.update("UPDATE world_clock SET tick=? WHERE session_id=?",i,sid);
   roles.recordAttempt(sid,a,"own_scene","尝试".repeat(120),Map.of(),Map.of("awake",true,"activity",("第"+i+"次真实结果")+"结果".repeat(115),"percepts",List.of("观察".repeat(90),"观察".repeat(90),"观察".repeat(90))));
  }
  String context=roles.attemptsContext(sid,a,"own_scene");assertTrue(context.contains("第5次真实结果"));assertFalse(context.contains("第2次真实结果"));assertTrue(context.length()<1300);
 }
 @Test void actualNpcPromptReceivesPreviouslyExecutedAttempts() throws Exception {
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES('continuity_scene_it',?,'木闸',?,'木匠检查木闸',0,0)",sid,json.writeValueAsString(List.of(a,b)));
  roles.recordAttempt(sid,a,"continuity_scene_it","维修杆探入闸缝后受阻",Map.of(),Map.of("awake",true,"activity","裂缝深度已确认，杆尖被硬物阻挡","percepts",List.of("淤泥里有硬阻碍")));
  org.mockito.Mockito.when(model.chatSubForJson(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call->{
   List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");
   assertTrue(prompt.contains("维修杆探入闸缝后受阻"));assertTrue(prompt.contains("裂缝深度已确认"));
   String payload="{\"behavior\":\"IGNORE\"}";return new LLMResponse(payload,payload,0,0,0,0);
  });
  assertFalse(agents.decideNpcBehavior(sid,a,"没有新增行动","木匠在木闸边",List.of(a,b)).usedFallback());
 }

 void proof(String text){
  var scene=new com.bs.narrative.domain.GameScene();scene.setSceneId("continuity_proof_it");scene.setLocationName("木闸");scene.setPresentGods(List.of(a,b));
  int round=1+jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='HEARD'",Integer.class,sid);
  knowledge.hear(sid,b,a,text,scene,2,round);
 }
 com.fasterxml.jackson.databind.node.ObjectNode topic(String name,String status,String basis,boolean repeated) throws Exception{
  var n=(com.fasterxml.jackson.databind.node.ObjectNode)exchange(status,basis,repeated);((com.fasterxml.jackson.databind.node.ObjectNode)n.path("exchange_update")).put("topic",name);return n;
 }
 String ref(String name){return jdbc.queryForObject("SELECT source_ref FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TALK_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.topic'))=?",String.class,sid,a,name);}
 JsonNode saved(String name) throws Exception{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,a,ref(name)));}
 @Test void separateTopicsWithSamePartnerSurviveAndRevisitingOnlyUpdatesOne() throws Exception{
  proof("断木卡槽");proof("报酬需完工再付");proof("只能先付一半");
  roles.remember(sid,a,topic("卡闸原因","ANSWERED","断木卡槽",false),b+"断木卡槽");
  roles.remember(sid,a,topic("维修报酬","CONDITIONAL","报酬需完工再付",false),b+"报酬需完工再付");
  var n=topic("换个表述","CONDITIONAL","只能先付一半",true);((com.fasterxml.jackson.databind.node.ObjectNode)n.path("exchange_update")).put("topic_id",ref("维修报酬"));
  assertFalse(roles.remember(sid,a,n,b+"只能先付一半"));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
  assertEquals("断木卡槽",saved("卡闸原因").path("basis").asText());assertEquals("只能先付一半",saved("维修报酬").path("basis").asText());
  assertEquals("报酬需完工再付",saved("维修报酬").path("history").get(0).path("basis").asText());
 }
 @Test void punctuationNormalizesButAnotherIssueIsNotSilentlyMerged() throws Exception{
  proof("断木卡槽");for(String t:List.of("卡 闸 原因？","卡闸原因","卡闸报酬"))roles.remember(sid,a,topic(t,"ANSWERED","断木卡槽",false),b+"断木卡槽");
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
 }
 @Test void promptSubstringWithoutActualHearingCannotBecomeAnAnswer() throws Exception{
  assertFalse(roles.remember(sid,a,exchange("ANSWERED","断木卡槽",true),b+"断木卡槽"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
 }
 @Test void invalidOrAnotherOwnersTopicIdIsRejected() throws Exception{
  proof("断木卡槽");var n=(com.fasterxml.jackson.databind.node.ObjectNode)exchange("ANSWERED","断木卡槽",false);
  var x=(com.fasterxml.jackson.databind.node.ObjectNode)n.path("exchange_update");x.put("topic_id","talk:foreign:abcd");roles.remember(sid,a,n,b+"断木卡槽");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
 }
 @Test void legacySlotIsPreservedWhenNewIssueStarts() throws Exception{
  proof("断木卡槽");proof("报酬未定");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧交涉',?,'TALK_STATE','','','',1)",sid,a,"talk:"+b,json.writeValueAsString(Map.of("partner",b,"topic","卡闸原因","status","ANSWERED","detail","卡住","basis","断木卡槽","sinceTick",1,"repeats",0)));
  roles.remember(sid,a,topic("报酬","UNANSWERED","报酬未定",false),b+"报酬未定");
  assertEquals("断木卡槽",saved("卡闸原因").path("basis").asText());assertFalse(ref("卡闸原因").equals("talk:"+b));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
 }
 @Test void latestUpdatedOldTopicIsIncludedBeforeOlderInsertions() throws Exception{
  proof("断木卡槽");
  for(int i=0;i<12;i++){jdbc.update("UPDATE world_clock SET tick=? WHERE session_id=?",i+2,sid);roles.remember(sid,a,topic("问题"+i,"ANSWERED","断木卡槽",false),b+"断木卡槽");}
  jdbc.update("UPDATE world_clock SET tick=20 WHERE session_id=?",sid);roles.remember(sid,a,topic("问题0","ANSWERED","断木卡槽",true),b+"断木卡槽");
  String ctx=roles.context(sid,a);assertTrue(ctx.contains("\"topic\":\"问题0\""));assertTrue(ctx.length()<3000);
  for(String line:ctx.split("\n"))if(line.startsWith("TALK_STATE："))assertTrue(json.readTree(line.substring("TALK_STATE：".length(),line.length()-1)).isObject());
 }
 @Test void recipientAndSpeakerEvidenceRemainSeparate() throws Exception{
  var speech=Map.of("id",b,"name","看守","to","third_party","text","断木卡槽");
  jdbc.update("INSERT INTO npc_memory(session_id,god_id,memory_type,memory_content,accuracy) VALUES(?,?,'PERSONAL',?,100)",sid,a,"【现场交涉】"+json.writeValueAsString(Map.of("speech",List.of(speech))));
  roles.remember(sid,a,exchange("ANSWERED","断木卡槽",false),b+"断木卡槽");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
  jdbc.update("INSERT INTO npc_memory(session_id,god_id,memory_type,memory_content,accuracy) VALUES(?,?,'PERSONAL',?,100)",sid,a,"【现场交涉】"+json.writeValueAsString(Map.of("speech",List.of(Map.of("id",a,"to",b,"text","为何卡闸")))));
  roles.remember(sid,a,exchange("UNANSWERED","为何卡闸",false),b+"为何卡闸");assertEquals("UNANSWERED",saved("卡闸原因").path("status").asText());
 }

 @Test void aggregateWorldHistoryCannotBypassOwnHearingContext() throws Exception{
  jdbc.update("INSERT INTO world_book(session_id,round_no,location_name,event_type,event_description,actor_ids) VALUES(?,1,'木闸','ACTION','前半刻未听到的暗号Q589',?)",sid,json.writeValueAsString(List.of(a)));
  org.mockito.Mockito.when(model.chatSubForJson(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);assertFalse(msgs.get(1).get("content").contains("Q589"));String payload="{\"behavior\":\"IGNORE\"}";return new LLMResponse(payload,payload,0,0,0,0);});
  assertFalse(agents.decideNpcBehavior(sid,a,"没有新增行为","木闸边",List.of(a,b)).usedFallback());
 }
 @Test void internalPunctuationCannotMergeDifferentQuantities() throws Exception{
  proof("等我确认");for(String t:List.of("支付1-2枚","支付12枚"))roles.remember(sid,a,topic(t,"CONDITIONAL","等我确认",false),b+"等我确认");
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TALK_STATE'",Integer.class,sid));
 }

 @Test void differentSpeechInSameRoundIsNotLostAndExactDeliveryIsIdempotent(){
  var scene=new com.bs.narrative.domain.GameScene();scene.setSceneId("heard_scene_it");scene.setLocationName("木闸");scene.setPresentGods(List.of(a,b));
  knowledge.hear(sid,b,a,"第一刻要求检查卡槽",scene,2,1);knowledge.hear(sid,b,a,"第二刻已经查明裂缝",scene,3,1);
  knowledge.hear(sid,b,a,"第二刻补充不能开闸",scene,3,1);knowledge.hear(sid,b,a,"第二刻补充不能开闸",scene,3,1);
  assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD'",Integer.class,sid,a));
  String ctx=knowledge.privateContext(sid,a);assertTrue(ctx.contains("检查卡槽"));assertTrue(ctx.contains("查明裂缝"));assertTrue(ctx.contains("不能开闸"));
 }
}
