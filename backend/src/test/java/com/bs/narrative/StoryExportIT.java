package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class StoryExportIT {
 @Autowired StoryExportService export;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired MockMvc mvc;
 @Autowired PlatformTransactionManager transactions;@MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();final String pid="p_demo";
 String sid,hero,voice,scene;
 @BeforeEach void seed()throws Exception{
  String tag=UUID.randomUUID().toString().substring(0,10);sid="export_"+tag;hero="export_h_"+tag;voice="export_v_"+tag;scene="export_c_"+tag;
  for(String id:List.of(hero,voice))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion) VALUES(?,?,?,'MORTAL','平静')",id,sid,id.equals(hero)?"林舟":"渡者");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'渡口','[]','公开的渡口')",scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,?,?,'PLAYING')",sid,pid,hero,scene);
  jdbc.update("INSERT INTO game_runtime(session_id,branch_epoch,revision) VALUES(?,2,9)",sid);
  jdbc.update("INSERT INTO session_world(session_id,definition) VALUES(?,?)",sid,"{\"title\":\"渡口余灯\",\"introduction\":\"此间以渡船连接两岸。\",\"questions\":[\"PRIVATE_QUESTION\"],\"hidden\":\"PRIVATE_WORLD_FIELD\"}");
 }
 void opening(){jdbc.update("INSERT INTO character_creation(session_id,player_id,question_1,question_2,question_3,answer_1,birth_story,status,view_mode) VALUES(?,?,'一','二','三','PRIVATE_ANSWER','林舟生于渡口，后来接手一盏旧灯。','COMPLETED','DREAMER')",sid,pid);}
 void log(int round,String prose){jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,?,'PRIVATE_PLAYER_INPUT','OBSERVE','NO_OP',?,?)",sid,pid,scene,round,prose,"{\"secret\":\"PRIVATE_THOUGHT\",\"operationStatus\":\"COMPLETED\",\"timeProgress\":{\"from\":\"第3日·上午\",\"to\":\"第3日·下午\",\"basis\":\"PRIVATE_TIME_BASIS\"}}");}
 String document()throws Exception{var out=new ByteArrayOutputStream();export.write(sid,pid,out,info->{});return out.toString(StandardCharsets.UTF_8);}
 String token(){return auth.login("player_demo","123456").get("token").toString();}
 @AfterEach void cleanup(){
  verifyNoInteractions(model);
  // Only this test's explicit, random export_* fixture is removed. No player state is changed.
  for(String table:List.of("role_knowledge","interaction_log","character_creation","session_world","game_runtime","game_session","game_scene","god_profile"))jdbc.update("DELETE FROM "+table+" WHERE session_id=?",sid);
 }
 @Test void exportsEveryChapterBeyondUiAndServerPageSizesWithoutPrivateData()throws Exception{
  opening();for(int i=1;i<=205;i++)log(i,"narrator：公开第"+i+"段。\n\n保留段落与引号“你好”。\n"+voice+"：公开台词"+i);
  String text=document();assertEquals('\ufeff',text.charAt(0));assertTrue(text.contains("此间以渡船连接两岸。"));assertTrue(text.contains("序章 · 来路"));assertTrue(text.contains("渡者：公开台词205"));
  int previous=-1;for(int i=1;i<=205;i++){int at=text.indexOf("第 "+i+" 段故事");assertTrue(at>previous,"All chapters must remain in chronological order");previous=at;}
  assertTrue(text.contains("第3日·上午 → 第3日·下午"));assertTrue(text.contains("公开第1段。\r\n\r\n保留段落"));assertTrue(text.contains("共导出 205 段"));assertTrue(text.stripTrailing().endsWith(StoryExportService.COMPLETE_MARKER));
  for(String secret:List.of("PRIVATE_","rule_snapshot",hero,voice,"narrator："))assertFalse(text.contains(secret),secret);
 }
 @Test void exportsTheOpeningBeforeAnyActionAndDoesNotAdvanceTheSave()throws Exception{
  opening();var before=jdbc.queryForMap("SELECT branch_epoch,revision,active_operation FROM game_runtime WHERE session_id=?",sid);
  String text=document();assertTrue(text.contains("林舟生于渡口"));assertTrue(text.contains("共导出 0 段"));assertEquals(before,jdbc.queryForMap("SELECT branch_epoch,revision,active_operation FROM game_runtime WHERE session_id=?",sid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id=?",Integer.class,sid));
 }
 @Test void exportsAnInactiveSaveWithoutSwitchingThePlayersCurrentGame()throws Exception{
  log(1,"narrator：留存在旧档的公开故事。");var before=jdbc.queryForMap("SELECT active_session_id,current_scene_id,god_id FROM player_profile WHERE player_id=?",pid);
  assertTrue(document().contains("旧档的公开故事"));assertEquals(before,jdbc.queryForMap("SELECT active_session_id,current_scene_id,god_id FROM player_profile WHERE player_id=?",pid));
 }
 @Test void retainsPublishedPartialAndCancelledProseButSkipsEmptyWrappers()throws Exception{
  log(1,"narrator：");log(2,voice+"：");log(3,"narrator：narrator：已经发生的公开片段。");
  jdbc.update("UPDATE interaction_log SET rule_snapshot=JSON_SET(rule_snapshot,'$.operationStatus','PARTIAL') WHERE session_id=? AND round_no=3",sid);
  String text=document();assertFalse(text.contains("第 1 段故事"));assertFalse(text.contains("第 2 段故事"));assertTrue(text.contains("第 3 段故事 · 已留存片段"));assertTrue(text.contains("已经发生的公开片段"));assertFalse(text.contains("narrator："));
  jdbc.update("UPDATE interaction_log SET rule_snapshot=JSON_SET(rule_snapshot,'$.operationStatus','CANCELLED') WHERE session_id=? AND round_no=3",sid);assertTrue(document().contains("已经发生的公开片段"));
 }
 @Test void anEmptyDraftFailsBeforeSendingAnAttachment()throws Exception{
  var out=new ByteArrayOutputStream();var started=new boolean[1];assertThrows(BusinessException.class,()->export.write(sid,pid,out,info->started[0]=true));assertFalse(started[0]);assertEquals(0,out.size());
  mvc.perform(get("/api/game/story-export").param("sessionId",sid).header("Authorization","Bearer "+token())).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
 }
 @Test void neitherUnauthenticatedNorForeignOwnersCanExportAnyBytes()throws Exception{
  opening();var out=new ByteArrayOutputStream();assertThrows(BusinessException.class,()->export.write(sid,null,out,info->fail("Unauthorized export")));assertThrows(BusinessException.class,()->export.write(sid,"not-the-owner",out,info->fail("Foreign export")));assertEquals(0,out.size());
  mvc.perform(get("/api/game/story-export").param("sessionId",sid)).andExpect(status().isUnauthorized());
  String username="export_other_"+UUID.randomUUID().toString().substring(0,8);auth.register(username,"Export_123456");
  var other=auth.login(username,"Export_123456");
  mvc.perform(get("/api/game/story-export").param("sessionId",sid).header("Authorization","Bearer "+other.get("token"))).andExpect(status().isForbidden());
 }
 @Test void responseIsAnAuthenticatedUtf8AttachmentWithSafeUnicodeFilename()throws Exception{
  opening();jdbc.update("UPDATE session_world SET definition=? WHERE session_id=?",json.createObjectNode().put("title","渡口/余灯:\r\n不应成为响应头").put("introduction","公开世界").toString(),sid);
  var response=mvc.perform(get("/api/game/story-export").param("sessionId",sid).header("Authorization","Bearer "+token())).andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
  String name=ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename();assertTrue(name.contains("林舟"));assertTrue(name.endsWith(".txt"));assertFalse(name.matches("(?s).*[\\\\/:*?\"<>|\\r\\n].*"));
  byte[] bytes=response.getContentAsByteArray();assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},Arrays.copyOf(bytes,3));assertTrue(new String(bytes,StandardCharsets.UTF_8).contains("林舟生于渡口"));
 }
 @Test void terminalExportIncludesOnlyTheSavedPublicEpilogueAndEvaluation()throws Exception{
  log(1,"narrator：主角在此停下旅程。");jdbc.update("UPDATE game_session SET status='RETIRED',death_summary='较早的公开终章' WHERE session_id=?",sid);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'life:ending','终章',?,'LIFE_ENDING','','','',0)",sid,hero,"{\"death_summary\":\"林舟活着留在渡口，旧灯仍亮着。\",\"final_evaluation\":\"他守住了承诺。\",\"privateThought\":\"PRIVATE_ENDING\"}");
  String text=document();assertTrue(text.contains("林舟活着留在渡口"));assertTrue(text.contains("他守住了承诺"));assertFalse(text.contains("PRIVATE_ENDING"));assertFalse(text.contains("death_summary"));assertTrue(text.indexOf("终章 ·")>text.indexOf("第 1 段故事"));
 }
 @Test void anOngoingLifeDoesNotExportAnObsoleteOrUnpublishedEnding()throws Exception{
  log(1,"narrator：旅程还在继续。");jdbc.update("UPDATE game_session SET death_summary='PRIVATE_OBSOLETE_ENDING' WHERE session_id=?",sid);assertFalse(document().contains("PRIVATE_OBSOLETE_ENDING"));assertFalse(document().contains("终章 ·"));
 }
 @Test void neverReadsDiscardedCheckpointsOrUnpublishedOperationCandidates()throws Exception{
  log(1,"narrator：当前分支的公开经过。");jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload) VALUES(?,1,'discarded','test',?,'COMPLETED',0,?)",sid,pid,"{\"story\":\"PRIVATE_DISCARDED_BRANCH\"}");
  jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload) VALUES(?,2,'draft','test',?,'PARTIAL',0,?)",sid,pid,"{\"sceneDraft\":{\"story\":\"PRIVATE_UNPUBLISHED_CANDIDATE\"}}");
  try{String text=document();assertTrue(text.contains("分支 2"));assertTrue(text.contains("当前分支的公开经过"));assertFalse(text.contains("PRIVATE_"));}
  finally{jdbc.update("DELETE FROM game_operation WHERE session_id=?",sid);}
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentRewindAndNewChaptersCannotMixSnapshotsAcrossExportPages()throws Exception{
  for(int i=1;i<=205;i++)log(i,"narrator：快照原文第"+i+"段。");
  var writer=new TransactionTemplate(transactions);writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  var out=new ByteArrayOutputStream();
  export.write(sid,pid,out,info->{assertEquals(2,info.branchEpoch());assertEquals(9,info.revision());writer.executeWithoutResult(status->{
   jdbc.update("UPDATE game_runtime SET branch_epoch=3,revision=99 WHERE session_id=?",sid);
   jdbc.update("DELETE FROM interaction_log WHERE session_id=? AND round_no=1",sid);
   jdbc.update("UPDATE interaction_log SET processed_dialog='narrator：PRIVATE_NEW_BRANCH' WHERE session_id=? AND round_no=205",sid);
   log(206,"narrator：PRIVATE_LATE_APPEND");
  });});
  String text=out.toString(StandardCharsets.UTF_8);assertTrue(text.contains("快照原文第1段"));assertTrue(text.contains("快照原文第205段"));assertTrue(text.contains("共导出 205 段"));assertFalse(text.contains("PRIVATE_NEW_BRANCH"));assertFalse(text.contains("PRIVATE_LATE_APPEND"));assertTrue(text.contains("分支 2"));
 }
}
