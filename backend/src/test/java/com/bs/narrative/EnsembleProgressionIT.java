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
class EnsembleProgressionIT {
 @Autowired GameActionService actions; @Autowired SessionStateService state; @Autowired JdbcTemplate jdbc;
 @Autowired EntityManager em; @Autowired NpcGoalService goals; @Autowired CharacterCreationService creation;
 @MockBean LLMProvider model;
 String sid="ensemble_it",pid="ensemble_user",role="ensemble_role",scene="ensemble_scene",bad="ensemble_bad",victim="ensemble_victim",guard="ensemble_guard";
 static LLMResponse response(String j){return new LLMResponse(j,j,0,0,0,0);}
 @BeforeEach void seed(){
  for(var id:List.of(role,bad,victim,guard))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',10,10,20,10,20,75,'[]')",id,sid,id.equals(role)?"你":id.equals(bad)?"守门恶徒":id.equals(victim)?"受困旅人":"守望者");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'雾海渡口',?,'守门恶徒挡住受困旅人的去路。',0,0)",scene,sid,"[\""+bad+"\",\""+victim+"\",\""+guard+"\"]");
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'ensemble_test','unused',?,?)",pid,role,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,status,view_mode) VALUES(?,?,'PLAYING','DREAMER')",sid,pid);state.initialize(sid,pid);
  when(model.chatForJson(anyList())).thenReturn(response("{\"visible\":{\"lines\":[]}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"IGNORE\",\"speech\":\"仍在处理自己的事\"}"));
 }
 String plan(String follow){return "{\"intent\":\"WAIT\",\"targetGodId\":\"\",\"waiting\":{\"condition\":\"LEAVE\",\"watchGodId\":\""+bad+"\",\"followIntent\":\"RESCUE\",\"followTargetGodId\":\""+victim+"\",\"followText\":\""+follow+"\"},\"visible\":{\"lines\":[]}}";}
 com.bs.narrative.dto.ActionResponse waitFor(String follow){when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("现场行动裁定者"))return response("{\"events\":[{\"actorId\":\""+role+"\",\"text\":\"你趁恶徒离开，接近旅人，带他退到渡口石栏旁，仍未离开渡口。\",\"view\":{\"activity\":\"接应旅人\"}}],\"rescueResult\":{\"targetId\":\""+victim+"\",\"status\":\"PARTIAL\",\"basis\":\"带他退到渡口石栏旁\",\"view\":{\"bound\":false,\"position\":\"渡口石栏旁\",\"activity\":\"退到石栏旁，仍未离开渡口\"}}}");return response(plan(follow));});return actions.processActionWithDb(new ActionRequest(pid,scene,"我隐蔽等待守门恶徒离开后，伺机救助受困旅人",null),sid);}
 @Test void waitAdvancesToDepartureThenExecutesOnlyAuthorizedRescueAndRewinds(){
  AtomicInteger turns=new AtomicInteger();List<String> actorPrompts=Collections.synchronizedList(new ArrayList<>());
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String u=messages.get(1).get("content");actorPrompts.add(u);boolean isBad=u.contains("姓名：守门恶徒");return response("{\"behavior\":\""+(isBad && turns.incrementAndGet()>=2?"LEAVE":"IGNORE")+"\"}");});
  var r=waitFor("伺机救助受困旅人");var w=(Map<?,?>)r.ruleSnapshot().get("waiting");assertEquals("EXECUTED",w.get("status"));assertEquals(2,w.get("phases"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));assertTrue(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,victim,"view:"+scene).contains("渡口石栏旁"));assertFalse(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,scene).contains(bad));
  assertTrue(actorPrompts.stream().noneMatch(u->u.contains("伺机救助") || u.contains("隐蔽等待")));assertEquals(1,jdbc.queryForObject("SELECT current_round FROM game_session WHERE session_id=?",Integer.class,sid));
  assertFalse(jdbc.queryForObject("SELECT content FROM player_memory WHERE session_id=? AND scope='ROUND'",String.class,sid).contains("强约束"));
  state.restore(sid,0);assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,scene).contains(bad));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM npc_decision_log WHERE session_id=?",Integer.class,sid));
 }
 @Test void conditionNotMetIsBoundedAndCanBeReadBack(){var r=waitFor("伺机救助受困旅人");var w=(Map<?,?>)r.ruleSnapshot().get("waiting");assertEquals("PENDING",w.get("status"));assertEquals(3,w.get("phases"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));assertEquals("PENDING",((Map<?,?>)actions.describeScene(pid,sid).get("waiting")).get("status"));verify(model,times(9)).chatSubForJson(anyList());}
 @Test void attackInterruptsWaitingInsteadOfForcingSuccess(){when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"ATTACK\",\"target_god_id\":\""+role+"\"}"));var r=waitFor("伺机救助受困旅人");assertEquals("INTERRUPTED",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));}
 @Test void invalidNpcTargetCannotBecomeAnAttackOnPlayer(){when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"ATTACK\",\"target_god_id\":\"foreign_role\"}"));var r=waitFor("伺机救助受困旅人");assertFalse(r.ruleSnapshot().containsKey("npcActions"));assertEquals(75,jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,role));}
 @Test void npcCanAttackAnotherNpcAndGuardCanIntercept(){
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String u=m.get(1).get("content");String behavior=u.contains("姓名：守门恶徒")?"ATTACK":u.contains("姓名：守望者")?"SUPPORT":"IGNORE";return response("{\"behavior\":\""+behavior+"\",\"target_god_id\":\""+victim+"\"}");});
  var req=new ActionRequest(pid,scene,"观察",new ActionRequest.StructuredIntent("OBSERVE",role,"",null,null,1.0,"观察"));var r=actions.processActionWithDb(req,sid);var attacks=(List<Map<String,Object>>)r.ruleSnapshot().get("npcActions");assertEquals(guard,attacks.get(0).get("targetGodId"));assertTrue(jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,victim)>=75);assertTrue(jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,role)>=75);
 }
 @Test void privateGoalsAreIsolatedAcrossActorCalls(){goals.setGoal(sid,bad,"恶徒独有秘密甲",8);goals.setGoal(sid,victim,"旅人独有秘密乙",8);List<String> prompts=Collections.synchronizedList(new ArrayList<>());when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);prompts.add(m.get(1).get("content"));return response("{\"behavior\":\"IGNORE\"}");});waitFor("伺机救助受困旅人");for(String u:prompts){if(u.contains("姓名：守门恶徒"))assertFalse(u.contains("旅人独有秘密乙"));if(u.contains("姓名：受困旅人"))assertFalse(u.contains("恶徒独有秘密甲"));}}
 @Test void modelFailurePausesWithoutInventingDeparture(){when(model.chatSubForJson(anyList())).thenThrow(new LLMApiException("test timeout",true));var r=waitFor("伺机救助受困旅人");var w=(Map<?,?>)r.ruleSnapshot().get("waiting");assertEquals("PAUSED",w.get("status"));assertEquals(1,w.get("phases"));assertTrue(jdbc.queryForObject("SELECT present_gods FROM game_scene WHERE scene_id=?",String.class,scene).contains(bad));}
 @Test void unauthorizedFollowUpIsNotExecuted(){when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"LEAVE\"}"));var r=waitFor("偷走所有宝物");assertNotEquals("EXECUTED",((Map<?,?>)r.ruleSnapshot().get("waiting")).get("status"));}
 @Test void incompleteTalentsRequireModelRepairAndSelectedBonusIsReal(){
  var start=creation.startGame(pid,"DREAMER");String created=(String)start.get("sessionId");when(model.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"清辉\",\"talent_description\":\"无敌回血\",\"power_level\":3,\"stat_key\":\"hui\"},{\"talent_name\":\"清辉\",\"power_level\":3}]}"));
  CreationWorldFixture.ready(creation,model,created,pid);
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->creation.submitAnswers(created,pid,"寒门","守人","隐忍"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM talent WHERE session_id=?",Integer.class,created));
  when(model.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"清辉\",\"power_level\":3,\"stat_key\":\"hui\"},{\"talent_name\":\"见微\",\"power_level\":1,\"stat_key\":\"wen\"},{\"talent_name\":\"守心\",\"power_level\":1,\"stat_key\":\"zhi\"}]}"));
  var result=creation.submitAnswers(created,pid,"寒门","守人","隐忍");var choices=(List<Map<String,Object>>)result.get("talents");assertEquals(3,choices.size());assertEquals(3,choices.stream().map(t->t.get("talentName")).distinct().count());assertTrue(choices.get(0).get("talentDescription").toString().contains("基础慧增加6点"));
  when(model.chatForJson(anyList())).thenReturn(response("{\"birth_story\":\"寒门入世\",\"initial_location\":{\"location_name\":\"石岸\",\"scene_description\":\"你空手站在石岸。\",\"present_npcs\":[]},\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[]}]}"));creation.selectTalent(created,pid,((Number)choices.get(0).get("talentId")).longValue());em.flush();String newRole=jdbc.queryForObject("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,created);assertEquals(15,jdbc.queryForObject("SELECT base_hui FROM god_profile WHERE god_id=?",Integer.class,newRole));assertEquals(75,jdbc.queryForObject("SELECT base_gui FROM god_profile WHERE god_id=?",Integer.class,newRole));
 }

 @Test void genericOpportunityWaitAdvancesPastIdleTickAndStopsOnRealMessage(){
  when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"CHANGE\"}}"));
  AtomicInteger seen=new AtomicInteger();
  when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> msgs=call.getArgument(0);boolean actor=msgs.get(1).get("content").contains("姓名：守门恶徒");boolean speak=actor && seen.incrementAndGet()==2;return response(speak?"{\"behavior\":\"RESPOND\",\"speech\":\"此处的路没有查完，先向旅人问清来处。\",\"inner_thought\":\"私密判断不进入事实\"}":"{\"behavior\":\"IGNORE\"}");});
  var result=actions.processActionWithDb(new ActionRequest(pid,scene,"继续等待，直到事情出现变化转机的时刻",null),sid);
  var w=(Map<?,?>)result.ruleSnapshot().get("waiting");assertEquals("READY",w.get("status"));assertEquals(2,w.get("phases"));assertEquals(2L,result.ruleSnapshot().get("worldTick"));
  assertTrue(result.lines().stream().anyMatch(l->l.speech().contains("向旅人问清")));
  assertFalse(result.ruleSnapshot().get("publicEvents").toString().contains("私密判断"));verify(model,times(6)).chatSubForJson(anyList());
  state.restore(sid,0);assertEquals(0L,actions.describeScene(pid,sid).get("worldTick"));
 }
 @Test void emptyResponseDoesNotFakeAnOpportunityAndIdleWaitIsBounded(){
  when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"CHANGE\"}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"speech\":\"\"}"));
  var result=actions.processActionWithDb(new ActionRequest(pid,scene,"等待到有变化",null),sid);
  assertEquals("PENDING",((Map<?,?>)result.ruleSnapshot().get("waiting")).get("status"));assertEquals(3L,result.ruleSnapshot().get("worldTick"));
  assertFalse(result.ruleSnapshot().get("publicEvents").toString().contains("尚未指定"));verify(model,times(9)).chatSubForJson(anyList());
 }
 @Test void changeWaitNeverExecutesAnUnspecifiedFollowUp(){
  when(model.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"CHANGE\",\"followIntent\":\"RESCUE\",\"followTargetGodId\":\""+victim+"\",\"followText\":\"救助旅人\"}}"));
  when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"LEAVE\"}"));
  var result=actions.processActionWithDb(new ActionRequest(pid,scene,"等到变化，再考虑要不要救助旅人",null),sid);
  assertEquals("READY",((Map<?,?>)result.ruleSnapshot().get("waiting")).get("status"));assertEquals("平静",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,victim));
 }
}


