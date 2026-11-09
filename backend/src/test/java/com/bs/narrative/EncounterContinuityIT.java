package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.domain.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class EncounterContinuityIT {
 @Autowired EncounterService encounters;
 @Autowired GameActionService actions; @Autowired SessionStateService state; @Autowired JdbcTemplate jdbc;
 @Autowired EntityManager em; @Autowired NpcGoalService goals; @Autowired CharacterCreationService creation;
 @MockBean LLMProvider model;
 String sid="continuity_it",pid="continuity_user",role="continuity_role",scene="continuity_scene",bad="continuity_bad",victim="continuity_victim",guard="continuity_guard";
 static LLMResponse response(String j){return new LLMResponse(j,j,0,0,0,0);}
 @BeforeEach void seed(){
  for(var id:List.of(role,bad,victim,guard))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',10,10,20,10,20,75,'[]')",id,sid,id.equals(role)?"你":id.equals(bad)?"守门恶徒":id.equals(victim)?"受困旅人":"守望者");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'雾海渡口',?,'守门恶徒挡住受困旅人的去路。',0,0)",scene,sid,"[\""+bad+"\",\""+victim+"\",\""+guard+"\"]");
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'continuity_test','unused',?,?)",pid,role,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,status,view_mode) VALUES(?,?,'PLAYING','DREAMER')",sid,pid);state.initialize(sid,pid);
  when(model.chatForJson(anyList())).thenReturn(response("{\"visible\":{\"lines\":[]}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"IGNORE\",\"speech\":\"仍在处理自己的事\"}"));
 }

 void waitIntent(){when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"CHANGE\"},\"visible\":{\"lines\":[]}}"));}
 @Test void ordinaryTalkIsBatchedAndNextPhaseKnowsActualPublicQuestion(){
  waitIntent();AtomicInteger ticks=new AtomicInteger();List<String> prompts=new ArrayList<>();
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String p=msgs.get(1).get("content");synchronized(prompts){prompts.add(p);}boolean actor=p.contains("姓名：守门恶徒");int n=actor?ticks.incrementAndGet():0;
   if(actor && n==1)return response("{\"behavior\":\"RESPOND\",\"speech\":\"旅人，你为何仍在渡口等候？\",\"inner_thought\":\"独有隐私甲\",\"decision_point\":\"NONE\"}");
   if(actor && n==2)return response("{\"behavior\":\"RESPOND\",\"speech\":\"我原以为桥已封闭，如今需要亲自核对这条说法。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"如今需要亲自核对这条说法\"}");
   return response("{\"behavior\":\"IGNORE\",\"speech\":\"\"}");});
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"等到有新消息",null),sid);
  assertEquals(2,((Map<?,?>)r.ruleSnapshot().get("waiting")).get("phases"));assertEquals("READY",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));
  assertTrue(prompts.stream().anyMatch(p->p.contains("姓名：受困旅人") && p.contains("旅人，你为何仍在渡口等候")));
  assertTrue(prompts.stream().filter(p->p.contains("姓名：受困旅人")).noneMatch(p->p.contains("独有隐私甲")));
  assertEquals(1,((List<?>)r.ruleSnapshot().get("decisionPoints")).size());
  String memory=encounters.context(sid,victim);assertTrue(memory.contains("仅确认说过"));assertFalse(memory.contains("独有隐私甲"));
  state.restore(sid,0);assertFalse(encounters.context(sid,victim).contains("旅人，你为何"));
 }
 @Test void repeatedSpeechDoesNotInterruptWaitEvenIfMarkedAsNewInformation(){
  waitIntent();when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"speech\":\"我仍守在此处，不会无故放弃旧船。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"不会无故放弃旧船\"}"));
  actions.processActionWithDb(new ActionRequest(pid,scene,"等待新消息",null),sid);
  var next=actions.processActionWithDb(new ActionRequest(pid,scene,"继续等待新消息",null),sid);
  assertEquals("PENDING",((Map<?,?>)next.ruleSnapshot().get("waiting")).get("status"));assertEquals(3,((Map<?,?>)next.ruleSnapshot().get("waiting")).get("phases"));assertTrue(((List<?>)next.ruleSnapshot().get("decisionPoints")).isEmpty());
 }
 @Test void someoneElsesQuestionAndInvalidBasisCannotDemandPlayerResponse(){
  waitIntent();when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+victim+"\",\"speech\":\"旅人，你愿意留下吗？\",\"decision_point\":\"PLAYER_CHOICE\",\"decision_basis\":\"你愿意留下吗\"}"));
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"等到变化",null),sid);assertEquals("PENDING",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));
  var d=new NpcAgentService.NpcDecision(bad,"RESPOND","原话","秘密",false,false,role,"","",null,false,null,"","NEW_INFORMATION","凭空新增线索");assertFalse(EncounterService.dialogueNode(d,true,role));
 }
 @Test void privateContinuityIsOwnedAndClaimsDoNotBecomeVerifiedFacts(){
  jdbc.update("INSERT INTO npc_decision_log(session_id,scene_id,god_id,behavior,speech,inner_thought,used_fallback) VALUES(?,?,?,'RESPOND','我已拥有通行印','甲的独有心理',false),(?,?,?,'RESPOND','另处的话','外周目的秘密',false)",sid,scene,bad,"foreign_continuity",scene,bad);
  assertTrue(encounters.context(sid,bad).contains("甲的独有心理"));assertFalse(encounters.context(sid,victim).contains("甲的独有心理"));assertFalse(encounters.context(sid,bad).contains("外周目的秘密"));
 }

 @Test void aConcretePlayerQuestionInterruptsSpecificWaitWithoutExecutingRescue(){
  when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"LEAVE\",\"watchGodId\":\""+bad+"\",\"followIntent\":\"RESCUE\",\"followTargetGodId\":\""+victim+"\",\"followText\":\"救助旅人\"}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+role+"\",\"speech\":\"你愿意留下核对这件事，还是继续等候？\",\"decision_point\":\"PLAYER_CHOICE\",\"decision_basis\":\"你愿意留下核对这件事\"}"));
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"等守门恶徒离开后，救助旅人",null),sid);
  assertEquals("INTERRUPTED",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));assertEquals(1,((Map<?,?>)r.ruleSnapshot().get("waiting")).get("phases"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));assertFalse(((List<?>)r.ruleSnapshot().get("decisionPoints")).isEmpty());
 }

 @Test void ignoringActorCannotPublishAnInventedDepartureOrHeldItemAsDialogue(){
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);assertTrue(msgs.get(0).get("content").contains("指玩家角色"));assertTrue(msgs.get(0).get("content").contains("空背包不能"));return response("{\"behavior\":\"IGNORE\",\"speech\":\"说罢提丹离开了渡口\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"提丹离开\"}");});
  // Real actor parser and caller-owned database context.
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"观察",new ActionRequest.StructuredIntent("OBSERVE",role,"",null,null,1.0,"观察")),sid);
  assertTrue(r.npcDecisions().stream().allMatch(d->d.speech().isBlank()));assertFalse(r.lines().toString().contains("提丹离开"));
 }

 @Test void questionToNpcDoesNotStopWaitBeforeItsActualAnswer(){
  waitIntent();AtomicInteger badTurns=new AtomicInteger(),guardTurns=new AtomicInteger();List<String> received=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");
   if(prompt.contains("姓名：守门恶徒") && badTurns.incrementAndGet()==1)return response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+guard+"\",\"speech\":\"守望者，你实际见过渡船离开吗？\",\"inner_thought\":\"不许旁人知道的动机乙\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"你实际见过渡船离开吗\"}");
   if(prompt.contains("姓名：守望者") && guardTurns.incrementAndGet()==2){received.add(prompt);return response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+bad+"\",\"speech\":\"我没有见过船离开，只能确认眼前有人挡住了旅人的去路。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"我没有见过船离开\"}");}
   return response("{\"behavior\":\"IGNORE\",\"speech\":\"\"}");});
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"继续等到事情有变化",null),sid);
  assertEquals(2,((Map<?,?>)r.ruleSnapshot().get("waiting")).get("phases"));assertEquals("READY",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));
  assertEquals(1,received.size());assertTrue(received.get(0).contains("对你说：守望者，你实际见过渡船离开吗"));assertFalse(received.get(0).contains("不许旁人知道的动机乙"));
  assertTrue(((List<?>)r.ruleSnapshot().get("decisionPoints")).get(0).toString().contains("我没有见过船离开"));
 }
 @Test void directedSpeechSurvivesCrowdTruncationWithoutGivingEveryoneTheSameQuestion(){
  var targetScene=new GameScene();targetScene.setSceneId(scene);targetScene.setLocationName("雾海渡口");targetScene.setPresentGods(new ArrayList<>(List.of(bad,victim,guard)));
  var chatter=new NpcAgentService.NpcDecision(bad,"RESPOND","很长的公开说明".repeat(60),"自己的秘密",false,false,"","","");
  var addressed=new NpcAgentService.NpcDecision(victim,"RESPOND","守望者，你愿意说明亲眼确认过什么吗？","旅人的秘密",false,false,guard,"","");
  encounters.record(sid,targetScene,role,1,List.of(chatter,addressed),List.of(bad,victim,guard,role),List.of());
  String receiver=encounters.context(sid,guard);assertTrue(receiver.contains("对你说：守望者，你愿意说明亲眼确认过什么吗"));assertFalse(receiver.contains("旅人的秘密"));
  assertFalse(encounters.context(sid,bad).contains("对你说：守望者"));
  var next=new NpcAgentService.NpcDecision(victim,"RESPOND","我已听见你的拒绝，暂不继续追问。","不公开的判断",false,false,guard,"","");
  encounters.record(sid,targetScene,role,2,List.of(next),List.of(bad,victim,guard,role),List.of());
  receiver=encounters.context(sid,guard);assertTrue(receiver.indexOf("对你说：我已听见你的拒绝")<receiver.indexOf("对你说：守望者"));assertTrue(receiver.length()<2100);
 }
 @Test void legacyUnaddressedMemoryStaysUnaddressedAndQuestionNodesStayConservative(){
  jdbc.update("INSERT INTO npc_memory(session_id,god_id,memory_type,memory_content,accuracy) VALUES(?,?,'PERSONAL',?,100)",sid,guard,"【现场交涉】{\"tick\":1,\"sceneId\":\""+scene+"\",\"location\":\"雾海渡口\",\"speech\":[{\"id\":\""+bad+"\",\"name\":\"守门恶徒\",\"text\":\"你为何不离开？\"}],\"events\":[]}");
  String context=encounters.context(sid,guard);assertTrue(context.contains("对象未记载"));assertFalse(context.contains("对你说：你为何不离开"));
  var pure=new NpcAgentService.NpcDecision(bad,"RESPOND","你见过船吗？船去哪了？","",false,false,victim,"","",null,false,null,"","NEW_INFORMATION","你见过船吗");assertFalse(EncounterService.dialogueNode(pure,true,role));
  var direct=new NpcAgentService.NpcDecision(bad,"RESPOND","你愿意留下核对吗？","",false,false,role,"","",null,false,null,"","PLAYER_CHOICE","你愿意留下核对吗");assertTrue(EncounterService.dialogueNode(direct,true,role));
  var mixed=new NpcAgentService.NpcDecision(bad,"RESPOND","我不再拦路。你还要继续等吗？","",false,false,victim,"","",null,false,null,"","NEW_INFORMATION","我不再拦路");assertTrue(EncounterService.dialogueNode(mixed,true,role));
 }

 @Test void executedInquiryQuestionReachesItsRecipientAndDoesNotBroadcastPrivateFindings(){
  waitIntent();AtomicInteger badTurns=new AtomicInteger(),guardTurns=new AtomicInteger();List<String> received=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String prompt=msgs.get(1).get("content");
   if(prompt.contains("姓名：守门恶徒") && badTurns.incrementAndGet()==1)return response("{\"behavior\":\"INQUIRE\",\"target_god_id\":\""+guard+"\",\"speech\":\"守望者，你亲眼看见渡船了吗？\",\"inner_thought\":\"我的私有顾虑丙\",\"decision_point\":\"NONE\"}");
   if(prompt.contains("姓名：守望者") && guardTurns.incrementAndGet()==2){received.add(prompt);return response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+bad+"\",\"speech\":\"我没有亲眼看见渡船，也不能保证它仍会来。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"我没有亲眼看见渡船\"}");}
   return response("{\"behavior\":\"IGNORE\",\"speech\":\"\"}");});
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"等待交涉的变化",null),sid);
  assertEquals(2,((Map<?,?>)r.ruleSnapshot().get("waiting")).get("phases"));assertEquals(1,received.size());assertTrue(received.get(0).contains("对你说：守望者，你亲眼看见渡船了吗"));assertFalse(received.get(0).contains("我的私有顾虑丙"));
  assertTrue(encounters.context(sid,guard).contains("守望者，你亲眼看见渡船了吗"));assertFalse(encounters.context(sid,victim).contains("对你说：守望者"));
  state.restore(sid,0);assertFalse(encounters.context(sid,guard).contains("守望者，你亲眼看见渡船了吗"));
 }

 @Test void eventWaitStopsForARealPlayerChoice(){
  when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"EVENT\",\"eventText\":\"仪式开始\"}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"target_god_id\":\""+role+"\",\"speech\":\"你愿意帮忙守住桥头吗？\",\"decision_point\":\"PLAYER_CHOICE\",\"decision_basis\":\"你愿意帮忙守住桥头吗\"}"));
  var r=actions.processActionWithDb(new ActionRequest(pid,scene,"等待仪式开始",null),sid);
  assertEquals("INTERRUPTED",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));assertEquals(1,((Map<?,?>)r.ruleSnapshot().get("waiting")).get("phases"));
 }
 @Test void speechDuringActionReachesWitnessMemoryAndNextPhase(){
  waitIntent();AtomicInteger count=new AtomicInteger();List<String> prompts=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);String p=msgs.get(1).get("content");prompts.add(p);
   if(p.contains("姓名：守门恶徒")){if(count.incrementAndGet()==1)return response("{\"behavior\":\"ACT\",\"speech\":\"旅人，你知道桥头守卫去了哪里？\",\"inner_thought\":\"不可泄露的心理\",\"target_god_id\":\""+victim+"\",\"decision_point\":\"NONE\"}");return response("{\"behavior\":\"RESPOND\",\"speech\":\"我发现桥头已被封锁。\",\"decision_point\":\"NEW_INFORMATION\",\"decision_basis\":\"桥头已被封锁\"}");}
   return response("{\"behavior\":\"IGNORE\"}");});
  actions.processActionWithDb(new ActionRequest(pid,scene,"等待新消息",null),sid);
  assertTrue(prompts.stream().anyMatch(p->p.contains("姓名：受困旅人") && p.contains("桥头守卫去了哪里")));
  assertTrue(encounters.context(sid,victim).contains("桥头守卫去了哪里"));assertFalse(encounters.context(sid,victim).contains("不可泄露的心理"));
 }


 @Test void directedRecipientIsKeptEvenWhenRecipientCannotHear(){
  var s=new GameScene();s.setSceneId(scene);s.setLocationName("雾海渡口");s.setPresentGods(List.of(bad,victim,guard));
  var d=new NpcAgentService.NpcDecision(bad,"RESPOND","旅人，你醒了再回答。","",false,false,victim,"","",null,false,null,"","NONE","");
  encounters.record(sid,s,role,1,List.of(d),List.of(bad,guard),List.of());
  assertTrue(encounters.context(sid,guard).contains("受困旅人"));assertFalse(encounters.context(sid,guard).contains("对你说：旅人"));
  assertFalse(encounters.context(sid,victim).contains("你醒了再回答"));
 }
 @Test void staleOmniscientSummaryDoesNotReachLateListener(){
  jdbc.update("UPDATE game_scene SET scene_summary=CONCAT(scene_summary,'【当前局势】无人听见的旧谈话暗号Z314') WHERE scene_id=?",scene);em.clear();
  List<String> prompts=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);prompts.add(msgs.get(1).get("content"));return response("{\"behavior\":\"IGNORE\"}");});
  waitIntent();actions.processActionWithDb(new ActionRequest(pid,scene,"等待变化",null),sid);
  assertFalse(prompts.isEmpty());assertTrue(prompts.stream().noneMatch(p->p.contains("旧谈话暗号Z314")));
 }
}
