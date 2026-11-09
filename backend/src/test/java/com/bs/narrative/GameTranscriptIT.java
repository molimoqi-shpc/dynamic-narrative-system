package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class GameTranscriptIT {
 void opening(String status){jdbc.update("INSERT INTO character_creation(session_id,player_id,question_1,question_2,question_3,answer_1,birth_story,status,view_mode) VALUES(?,?,'问一','问二','问三','PRIVATE_CREATION_ANSWER','出生于溪畔，后来来到渡口。',?,'DREAMER')",sid,pid,status);}
 @Test void openingIsAvailableBeforeAnyTurnAndContainsOnlySavedPublicProse(){seed();opening("COMPLETED");var result=transcript.read(sid,pid,null);assertTrue(((List<?>)result.get("turns")).isEmpty());assertEquals(Map.of("title","序章 · 来路","text","出生于溪畔，后来来到渡口。"),result.get("opening"));assertFalse(result.toString().contains("PRIVATE_CREATION_ANSWER"));assertThrows(BusinessException.class,()->transcript.read(sid,"foreign",null));}
 @Test void incompleteOrLegacyCreationDoesNotInventAnOpening(){seed();assertEquals(Map.of(),transcript.read(sid,pid,null).get("opening"));opening("STORY_GENERATED");assertEquals(Map.of(),transcript.read(sid,pid,null).get("opening"));}
 @Test void openingIsIndependentOfTurnPagination(){seed();opening("COMPLETED");for(int i=1;i<=41;i++)log(i);var first=transcript.read(sid,pid,null);var older=transcript.read(sid,pid,((Number)first.get("nextBeforeId")).longValue());assertEquals(first.get("opening"),older.get("opening"));assertEquals(40,((List<?>)first.get("turns")).size());assertEquals(1,((List<?>)older.get("turns")).size());}
 @Autowired GameTranscriptService transcript;@Autowired AuthService auth;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
 String sid="qa_transcript_"+UUID.randomUUID().toString().substring(0,8),pid,scene,voice;
 void seed(){String user="qa_transcript_"+UUID.randomUUID().toString().substring(0,8);auth.register(user,"Transcript_123");var account=jdbc.queryForMap("SELECT player_id,current_scene_id FROM player_profile WHERE username=?",user);pid=account.get("player_id").toString();scene=account.get("current_scene_id").toString();voice="voice_"+UUID.randomUUID().toString().substring(0,8);jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_ben) VALUES(?,?,'渡者','MORTAL','平静',0)",voice,sid);jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);}
 void log(int round){jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,?,?,?,'OBSERVE','NO_OP',?,?)",sid,pid,scene,round,"我观察第"+round+"刻","narrator：公开故事\n"+voice+"：公开台词","{\"secret\":\"PRIVATE_THOUGHT_NEVER_RETURN\"}");}
 @Test void onlyPublicNarrationIsRestoredWithSpeakerNames(){seed();log(1);var result=transcript.read(sid,pid,null);var turns=(List<?>)result.get("turns");assertEquals(1,turns.size());assertTrue(result.toString().contains("渡者"));assertTrue(result.toString().contains("公开台词"));assertFalse(result.toString().contains("PRIVATE_THOUGHT_NEVER_RETURN"));assertFalse(result.toString().contains("rule_snapshot"));}
 @Test void rejectsForeignOwnerAndUnauthenticatedRead() throws Exception {seed();log(1);assertThrows(BusinessException.class,()->transcript.read(sid,"p_demo",null));assertThrows(BusinessException.class,()->transcript.read(sid,null,null));mvc.perform(get("/api/game/transcript").param("sessionId",sid)).andExpect(status().isUnauthorized());}
 @Test void paginatedHistoryKeepsChronologyWithoutRepeatingRows(){seed();for(int i=1;i<=43;i++)log(i);var first=transcript.read(sid,pid,null);var rows=(List<Map<String,Object>>)first.get("turns");assertEquals(40,rows.size());assertEquals(4,rows.get(0).get("round"));assertEquals(43,rows.get(39).get("round"));var second=transcript.read(sid,pid,((Number)first.get("nextBeforeId")).longValue());var earlier=(List<Map<String,Object>>)second.get("turns");assertEquals(3,earlier.size());assertEquals(1,earlier.get(0).get("round"));assertEquals(false,second.get("hasMore"));assertTrue(earlier.stream().noneMatch(e->rows.stream().anyMatch(r->r.get("id").equals(e.get("id")))));}

 @Test void chapterTimeWaitAndTimelineSurviveReloadWithoutInternalEffects(){seed();log(1);
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?","{\"storyStartSeconds\":2,\"storySeconds\":7,\"elapsedSeconds\":5,\"operationStatus\":\"COMPLETED\",\"eventTimeline\":[{\"text\":\"渡者离开了此地。\",\"endSeconds\":7,\"effects\":{\"toSceneId\":\"HIDDEN_DESTINATION\"}}],\"waitResults\":[{\"status\":\"SATISFIED\",\"atSeconds\":7,\"conditionText\":\"等渡者离开\",\"internal\":\"PRIVATE_WAIT\"}],\"secret\":\"PRIVATE_OTHER\"}",sid);
  var turn=(Map<?,?>)((List<?>)transcript.read(sid,pid,null).get("turns")).get(0);assertEquals(5L,turn.get("elapsedSeconds"));assertEquals(2L,turn.get("storyStartSeconds"));assertEquals(7L,turn.get("storySeconds"));assertTrue(turn.toString().contains("等渡者离开"));assertTrue(turn.toString().contains("渡者离开了此地"));assertFalse(turn.toString().contains("HIDDEN_DESTINATION"));assertFalse(turn.toString().contains("PRIVATE_WAIT"));assertFalse(turn.toString().contains("PRIVATE_OTHER"));
 }
 @Test void storyChoiceSurvivesReloadWithoutPrivatePlannerFields(){seed();log(1);
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?","{\"storyProgress\":{\"beats\":2,\"requiresInput\":true,\"decisionType\":\"ROMANCE\",\"reason\":\"她向你表白，你准备如何回应？\",\"plannerPrompt\":\"PRIVATE_PLAN\"}}",sid);
  var turn=(Map<?,?>)((List<?>)transcript.read(sid,pid,null).get("turns")).get(0);assertTrue(turn.get("storyProgress").toString().contains("表白"));assertFalse(turn.toString().contains("PRIVATE_PLAN"));
 }
 @Test void publicDecisionPointSurvivesReloadWithoutPrivateSnapshotFields(){seed();log(1);
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?","{\"decisionPoints\":[{\"text\":\"渡者正在等待你的回应：愿意核对消息吗？\",\"phase\":2,\"innerThought\":\"PRIVATE_NODE_THOUGHT\"}],\"secret\":\"PRIVATE_OTHER\"}",sid);
  String result=transcript.read(sid,pid,null).toString();assertTrue(result.contains("愿意核对消息吗"));assertFalse(result.contains("PRIVATE_NODE_THOUGHT"));assertFalse(result.contains("PRIVATE_OTHER"));
 }
 @Test void actualDecisionConsequencesSurviveHttpReloadInFullWithoutPrivatePlanning()throws Exception{seed();log(1);
  String stakes="若答应新清算担保，单据不实就须承担连带赔偿。".repeat(30);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var snapshot=json.createObjectNode();
  snapshot.putObject("storyProgress").put("requiresInput",true).put("reason","是否为陆绫的新清算担保？").put("stakes",stakes).put("plannerPrompt","PRIVATE_UNPUBLISHED_PLAN");
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?",snapshot.toString(),sid);
  String token=auth.login(jdbc.queryForObject("SELECT username FROM player_profile WHERE player_id=?",String.class,pid),"Transcript_123").get("token").toString();
  mvc.perform(get("/api/game/transcript").param("sessionId",sid).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.turns[0].storyProgress.stakes").value(stakes)).andExpect(jsonPath("$.turns[0].storyProgress.plannerPrompt").doesNotExist());
 }

 @Test void reloadKeepsHistoricalDialogueButDoesNotReviveAnExplicitlyInactivePrompt(){seed();log(1);
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?","{\"decisionPoints\":[{\"text\":\"旧交涉\",\"phase\":1}],\"activeDecisionPoints\":[],\"waiting\":{\"phases\":3}}",sid);
  var turn=(Map<?,?>)((List<?>)transcript.read(sid,pid,null).get("turns")).get(0);
  assertEquals(1,((List<?>)turn.get("decisionPoints")).size());assertTrue(((List<?>)turn.get("activeDecisionPoints")).isEmpty());
 }
 @Test void reloadOfLegacyWaitFiltersPastPhaseWithoutDeletingHistory(){seed();log(1);
  jdbc.update("UPDATE interaction_log SET rule_snapshot=? WHERE session_id=?","{\"decisionPoints\":[{\"text\":\"前刻的问题\",\"phase\":1},{\"text\":\"此刻提议\",\"phase\":3}],\"waiting\":{\"phases\":3}}",sid);
  var turn=(Map<?,?>)((List<?>)transcript.read(sid,pid,null).get("turns")).get(0);
  assertEquals(2,((List<?>)turn.get("decisionPoints")).size());assertEquals(1,((List<?>)turn.get("activeDecisionPoints")).size());assertTrue(turn.get("activeDecisionPoints").toString().contains("此刻提议"));
 }

}
