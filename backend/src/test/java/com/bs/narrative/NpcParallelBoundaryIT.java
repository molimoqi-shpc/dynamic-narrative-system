package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class NpcParallelBoundaryIT {
 @Autowired NpcAgentService actors;@Autowired NpcGoalService goals;@Autowired SessionStateService states;
 @Autowired JdbcTemplate jdbc;@Autowired EntityManager em;@Autowired PlatformTransactionManager transactions;
 @MockBean LLMProvider model;
 @Test void privateSnapshotsStayIsolatedAndOnlyCallerAppliesChanges(){
  new TransactionTemplate(transactions).execute(status->{status.setRollbackOnly();String sid="qa_parallel_boundary",pid="qa_parallel_player",a="qa_parallel_a",b="qa_parallel_b",scene="qa_parallel_scene";
   jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui) VALUES(?,?,'角色甲','MORTAL','平静',75),(?,?,'角色乙','MORTAL','平静',75)",a,sid,b,sid);
   jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'试验地点',?,'无冲突')",scene,sid,"[\""+a+"\",\""+b+"\"]");
   jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'qa_parallel_boundary','unused',?,?)",pid,a,scene,sid);
   jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);states.initialize(sid,pid);
   goals.setGoal(sid,a,"甲独有秘密目的",8);goals.setGoal(sid,b,"乙独有秘密目的",8);
   var barrier=new CountDownLatch(2);
   when(model.chatSubForJson(anyList())).thenAnswer(inv->{
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"worker must not access caller transaction");assertTrue(Thread.currentThread().getName().startsWith("npc-model-request"));
    List<Map<String,String>> messages=inv.getArgument(0);String text=messages.get(1).get("content");boolean first=text.contains("姓名：角色甲");
    assertTrue(text.contains(first?"甲独有秘密目的":"乙独有秘密目的"));assertFalse(text.contains(first?"乙独有秘密目的":"甲独有秘密目的"));
    assertEquals(first,text.contains("甲独有私语"));barrier.countDown();assertTrue(barrier.await(5,TimeUnit.SECONDS));
    String json="{\"behavior\":\"IGNORE\",\"speech\":\"\",\"emotional_state\":\""+(first?"谨慎":"安宁")+"\"}";
    var response=new LLMResponse(json,json,7,3,10,0.01);LLMUsage.record(response);return response;
   });
   LLMUsage.begin();
   var results=actors.decideAllNpcBehaviors(sid,List.of(a,b,a),"公开观察","无变化",List.of(a,b),Map.of(a,"甲独有私语"));
   assertEquals(List.of(a,b),results.stream().map(NpcAgentService.NpcDecision::godId).toList());assertTrue(results.stream().noneMatch(NpcAgentService.NpcDecision::usedFallback));
   em.flush();assertEquals("谨慎",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,a));assertEquals("安宁",jdbc.queryForObject("SELECT god_emotion FROM god_profile WHERE god_id=?",String.class,b));
   assertEquals(2,LLMUsage.totals().calls());assertEquals(14,LLMUsage.totals().input());return null;
  });
 }
}
