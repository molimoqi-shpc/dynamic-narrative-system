package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.domain.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** This must commit a real transaction: a rollback-only test transaction cannot catch this regression. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class SceneRepairCommitIT {
 @Autowired OpenSceneService open;@Autowired JdbcTemplate jdbc;@Autowired GodProfileRepository gods;
 @Autowired GameSceneRepository scenes;@Autowired PlatformTransactionManager transactions;@MockBean LLMProvider model;
 LLMResponse response(String json){return new LLMResponse(json,json,0,0,0,0);}
 @Test void correctedItemValidationCommitsButRepeatedInvalidResultStillRollsBack(){
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12),sid="repair_"+suffix,actor="repair_actor_"+suffix,scene="repair_scene_"+suffix;
  var tx=new TransactionTemplate(transactions);var calls=new AtomicInteger();
  try{
   tx.execute(status->{
    jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(?,?,'木匠','MORTAL','平静',10,10,10,10,20,75,'[]')",actor,sid);
    jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'木闸',?,'木闸轴座有一道裂缝，木匠站在闸边。',0,0)",scene,sid,"[\""+actor+"\"]");
    jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,1)",sid);return null;
   });
   String invalid="{\"events\":[{\"actorId\":\""+actor+"\",\"text\":\"木匠取来铁箍修理轴座。\"}]}";
   String valid="{\"events\":[{\"actorId\":\""+actor+"\",\"text\":\"木匠检查轴座，确认裂缝贯穿，当前没有工具无法修理。\",\"view\":{\"awake\":true,\"activity\":\"检查结束，当前缺少修理工具\",\"percepts\":[\"裂缝已经贯穿\"]}}]}";
   var decision=new NpcAgentService.NpcDecision(actor,"ACT","","",false,false,"","","",null,false,null,"","NONE","","检查裂缝并判断能否修理");
   when(model.chatForJson(anyList())).thenAnswer(call->{
    if(calls.incrementAndGet()==1)return response(invalid);
    List<Map<String,String>> msgs=call.getArgument(0);String repair=msgs.get(msgs.size()-1).get("content");
    assertTrue(repair.contains("uses数组"));assertTrue(repair.contains("只改工具名字"));assertTrue(repair.contains("所属检查"));
    return response(valid);
   });
   assertDoesNotThrow(()->tx.execute(status->open.resolve(sid,scenes.findById(scene).orElseThrow(),"",List.of(decision),List.of(),"")));
   assertEquals(2,calls.get());
   assertEquals("检查结束，当前缺少修理工具",open.view(sid,gods.findById(actor).orElseThrow(),scenes.findById(scene).orElseThrow()).get("activity"));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_ATTEMPT'",Integer.class,sid));
   jdbc.update("UPDATE world_clock SET tick=2 WHERE session_id=?",sid);
   doReturn(response(invalid)).when(model).chatForJson(anyList());
   assertThrows(com.bs.narrative.exception.BusinessException.class,()->tx.execute(status->open.resolve(sid,scenes.findById(scene).orElseThrow(),"",List.of(decision),List.of(),"")));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SCENE_ATTEMPT'",Integer.class,sid));
   assertTrue(gods.findById(actor).orElseThrow().getInventory().isEmpty());
  } finally {
   tx.execute(status->{jdbc.update("DELETE FROM role_knowledge WHERE session_id=?",sid);jdbc.update("DELETE FROM world_clock WHERE session_id=?",sid);jdbc.update("DELETE FROM game_scene WHERE session_id=?",sid);jdbc.update("DELETE FROM god_profile WHERE session_id=?",sid);return null;});
  }
 }
}
