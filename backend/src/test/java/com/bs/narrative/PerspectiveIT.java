package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.exception.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class PerspectiveIT {
 @Autowired PerspectiveService service;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired MockMvc mvc;
 final ObjectMapper json=new ObjectMapper();final String sid="perspective_test",pid="perspective_user";String token;long log;
 @BeforeEach void seed(){
  for(String id:List.of("perspective_self","perspective_core","perspective_extra","perspective_foreign"))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','PRIVATE_EMOTION','[\"秘密信物\"]')",id,id.endsWith("foreign")?"perspective_other":sid,id);
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('perspective_scene',?,'渡口','[\"perspective_core\",\"perspective_extra\"]','林野与二人在渡口')",sid);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,'perspective_self',?,?,'perspective_scene',?)",pid,pid,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("TestOnly123!"),sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,'perspective_self','perspective_scene','PLAYING')",sid,pid);
  jdbc.update("INSERT INTO game_runtime(session_id,branch_epoch,revision,story_seconds) VALUES(?,2,4,120)",sid);
  jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,'perspective_scene',1,'问路','TALK','NO_OP','narrator：二人已经问过路。','{\"storySeconds\":120,\"privateRule\":\"PRIVATE_SCORE\"}')",sid,pid);
  log=jdbc.queryForObject("SELECT MAX(log_id) FROM interaction_log WHERE session_id=?",Long.class,sid);
  var payload=json.createObjectNode().put("logId",log).put("playerRole","perspective_self").put("sceneFrame",0);payload.putArray("scenePassages").add("二人已经问过路。");var frames=payload.putObject("sceneDirections");frames.putObject("0").putObject("perspective_core").put("thought","PRIVATE_THOUGHT").put("intention","愿指路但不同行");frames.putObject("1").putObject("perspective_core").put("thought","UNCOMMITTED_SECRET");
  jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload) VALUES(?,2,'perspective_op','hash',?,'PARTIAL',1,?)",sid,pid,payload.toString());
  for(int i=0;i<25;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,'perspective_core',?,'私人记录',?,'TURN_EVENT','','perspective_scene','渡口',1)",sid,"memory:"+i,"{\"text\":\"PRIVATE_MEMORY_"+i+"\"}");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,'perspective_extra','stats','未确立属性','{\"sixStatsEstablished\":false}','SELF_PROFILE','','perspective_scene','渡口',1)",sid);
  for(String role:List.of("perspective_core","perspective_extra"))jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,importance,is_secret,info_source) VALUES(?,1,'故事第80秒',0,0,'渡口','CHARACTER_DOSSIER',?,?,5,true,'PERSONAL')",sid,"[\""+role+"\"]","{\"name\":\""+role+"\",\"narrativeTier\":\""+(role.endsWith("core")?"CORE":"EXTRA")+"\",\"psychology\":{\"roleInScene\":\"守船\",\"hiddenAgenda\":\"PRIVATE_AGENDA\"}}");
  token=auth.login(pid,"TestOnly123!").get("token").toString();
 }
 void omni(){jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);}
 @Test void limitedViewOmitsEveryPrivatePayloadEvenWhenRequestingARole()throws Exception{
  var result=service.read(sid,pid,"perspective_core",null);assertEquals(false,result.get("available"));assertEquals("DREAMER",result.get("viewMode"));String body=json.writeValueAsString(result);for(String word:List.of("PRIVATE_","character","memories","roles","decisions","ruleSnapshots"))assertFalse(body.contains(word));
 }
 @Test void newStoryDirectionsAreVisibleButUnpublishedFramesAreNot()throws Exception{
  omni();String body=json.writeValueAsString(service.read(sid,pid,"perspective_core",null));assertTrue(body.contains("PRIVATE_THOUGHT"));assertTrue(body.contains("PRIVATE_MEMORY_"));assertTrue(body.contains("PRIVATE_SCORE"));assertTrue(body.contains("PRIVATE_AGENDA"));assertFalse(body.contains("UNCOMMITTED_SECRET"));assertFalse(body.contains("perspective_foreign"));
  var directions=service.publishedDirections(sid);assertEquals(1,directions.size());assertNull(directions.get(0).get("isLying"),"Missing a lie judgment is not a finding of honesty");
 }
 @Test void removedChaptersCannotLeakOldBranchIntentions(){omni();jdbc.update("DELETE FROM interaction_log WHERE session_id=?",sid);assertTrue(service.publishedDirections(sid).isEmpty());}
 @Test void largeFrozenOperationsCanBeReadWithASmallSortBufferWithoutChangingThePublishedFrame(){
  omni();
  long previous=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
  try{
   jdbc.execute("SET SESSION sort_buffer_size=32768");
   jdbc.update("UPDATE game_operation SET payload=JSON_SET(payload,'$.largeFrozenContext',?) WHERE session_id=?","x".repeat(1024*1024),sid);
   jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload,updated_at) SELECT session_id,1,'older_copy',request_hash,player_id,status,fence,payload,DATE_SUB(updated_at,INTERVAL 1 DAY) FROM game_operation WHERE session_id=? AND operation_id='perspective_op'",sid);
   var directions=service.publishedDirections(sid);assertEquals(1,directions.size());
   assertEquals("perspective_op",directions.get(0).get("operationId"));assertEquals("PRIVATE_THOUGHT",directions.get(0).get("thought"));
   assertFalse(directions.toString().contains("UNCOMMITTED_SECRET"));assertFalse(directions.toString().contains("largeFrozenContext"));
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+previous);}
 }
 @Test void memoriesArePerCharacterPaginatedAndReadOnly(){
  omni();long before=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Long.class,sid);var first=service.read(sid,pid,"perspective_core",null);var second=service.read(sid,pid,"perspective_core",((Number)first.get("nextBefore")).longValue());assertEquals(20,((List<?>)first.get("memories")).size());assertEquals(5,((List<?>)second.get("memories")).size());assertEquals(false,second.get("hasMoreMemories"));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Long.class,sid));assertEquals(120L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
 }
 @Test void extrasStayBriefAndUnknownStatsRemainUnknown()throws Exception{omni();var result=service.read(sid,pid,"perspective_extra",null);var character=(Map<?,?>)result.get("character");assertEquals(false,character.get("sixStatsEstablished"));String dossier=json.writeValueAsString(character.get("dossier"));assertTrue(dossier.contains("守船"));assertFalse(dossier.contains("PRIVATE_AGENDA"));}
 @Test void foreignSessionAndForeignRolesAreRejected(){assertThrows(BusinessException.class,()->service.read(sid,"other","",null));omni();assertThrows(BusinessException.class,()->service.read(sid,pid,"perspective_foreign",null));}
 @Test void playerLocationFollowsTheActiveSaveRatherThanTheOldSessionPointer(){
  omni();jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('perspective_new',?,'北亭','[]','已抵达北亭')",sid);jdbc.update("UPDATE player_profile SET current_scene_id='perspective_new' WHERE player_id=?",pid);
  var character=(Map<?,?>)service.read(sid,pid,"perspective_self",null).get("character");assertTrue(character.get("location").toString().contains("北亭"));assertFalse(character.get("location").toString().contains("渡口"));assertEquals(100,character.get("health"));assertNotNull(character.get("body"));
 }
 @Test void actualHttpSwitchGatesMemoryAndDecisionsAndDoesNotAdvanceTheWorld()throws Exception{
  String header="Bearer "+token;
  mvc.perform(get("/api/game/memory").param("sessionId",sid).header("Authorization",header)).andExpect(status().isOk()).andExpect(jsonPath("$.memoryContext").value(""));
  mvc.perform(post("/api/game/view-mode").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\""+sid+"\",\"viewMode\":\"OMNISCIENT\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/game/perspective").param("sessionId",sid).param("roleId","perspective_core").header("Authorization",header)).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
  mvc.perform(get("/api/game/npc-decisions").param("sessionId",sid).header("Authorization",header)).andExpect(status().isOk()).andExpect(jsonPath("$.totalDecisions").value(1));
  mvc.perform(post("/api/game/view-mode").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\""+sid+"\",\"viewMode\":\"DREAMER\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/game/perspective").param("sessionId",sid).header("Authorization",header)).andExpect(jsonPath("$.available").value(false));
  mvc.perform(get("/api/game/npc-decisions").param("sessionId",sid).header("Authorization",header)).andExpect(jsonPath("$.totalDecisions").value(0));
  assertEquals(120L,jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid));
 }
}
