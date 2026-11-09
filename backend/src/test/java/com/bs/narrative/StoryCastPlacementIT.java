package com.bs.narrative;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.service.StoryCastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class StoryCastPlacementIT {
 @Autowired StoryCastService cast;@Autowired GameSceneRepository scenes;@Autowired JdbcTemplate jdbc;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();
 @Test void missingFinalLocationKeepsTheProfileWithoutTeleportingTheVisitorIntoThePlayersScene(){
  String sid="castplace_"+UUID.randomUUID().toString().substring(0,8);
  var current=new GameScene();current.setSceneId(sid+"_home");current.setSessionId(sid);current.setLocationName("同福记账房");current.setPresentGods(new ArrayList<>());scenes.saveAndFlush(current);
  var entries=json.createArrayNode();entries.addObject().put("name","何掌柜").put("profile","西街德昌掌柜").put("experience","在自己铺中展示旧账，客人随后离开。").put("situation","仍留德昌铺内");
  cast.prepare(sid,"op",0,entries);cast.remember(sid,"op",0,current,entries);
  String id=cast.idFor(sid,"op",0,"何掌柜");
  assertTrue(scenes.findBySessionId(sid).stream().noneMatch(s->s.getPresentGods().contains(id)));
  assertEquals("",jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SELF_PROFILE'",String.class,sid,id));
  assertEquals("仍留德昌铺内",jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_STORY_STATE'",String.class,sid,id));
  var shop=new GameScene();shop.setSceneId(sid+"_shop");shop.setSessionId(sid);shop.setLocationName("德昌杂货铺");shop.setPresentGods(new ArrayList<>(List.of(id)));scenes.saveAndFlush(shop);
  cast.remember(sid,"op",0,current,entries);
  assertFalse(scenes.findById(current.getSceneId()).orElseThrow().getPresentGods().contains(id));
  assertEquals(shop.getSceneId(),jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SELF_PROFILE'",String.class,sid,id));
  org.mockito.Mockito.verifyNoInteractions(model);
 }
}
