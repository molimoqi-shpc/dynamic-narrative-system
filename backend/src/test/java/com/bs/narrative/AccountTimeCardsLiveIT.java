package com.bs.narrative;

import com.bs.narrative.llm.*;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** One budget-bounded production archive call on an existing published chapter; all probe state rolls back. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","llm.max-retries=0"})
@EnabledIfEnvironmentVariable(named="LIVE_ACCOUNT_TIME_CARDS",matches="yes")
class AccountTimeCardsLiveIT {
 @Autowired JdbcTemplate jdbc;@Autowired StoryArchiveService archive;@Autowired StoryPlotService plots;
 @Autowired StoryContinuityService continuity;@Autowired OperationUsageService usage;@Autowired LLMConfig config;
 @Autowired PlatformTransactionManager manager;
 final ObjectMapper json=new ObjectMapper();
 @Test void reviewPublishedSixthChapterWithTheNormalProductionArchive() throws Exception {
  Path dir=Path.of(System.getenv("LIVE_CONTINUOUS_DIR"));var account=json.readTree(dir.resolve("account.json").toFile());
  assertTrue(account.path("username").asText().startsWith("continuousqa_"));String sid=account.path("sessionId").asText(),pid=account.path("playerId").asText();
  assertEquals(account.path("username").asText(),jdbc.queryForObject("SELECT username FROM player_profile WHERE player_id=? AND active_session_id=?",String.class,pid,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_runtime WHERE session_id=? AND active_operation IS NOT NULL",Integer.class,sid));
  if(!"REPLAY".equals(System.getenv("LIVE_ACCOUNT_TIME_CARDS_MODE")))assertFalse(Files.exists(dir.resolve("account-time-cards-raw.json")),"Do not repeat the paid probe");
  var saved=json.readTree(dir.resolve("step6-payload.json").toFile());var facts=(ObjectNode)saved.path("sceneFrameFacts").deepCopy();var draft=(ObjectNode)saved.path("sceneDraft").deepCopy();
  assertEquals(Files.readString(dir.resolve("step6-story.txt")).replace("\r\n","\n"),draft.path("story").asText().replace("\r\n","\n"));
  var input=archive.input(facts,draft);Files.writeString(dir.resolve("account-time-cards-input.json"),input.toPrettyString());
  boolean replay="REPLAY".equals(System.getenv("LIVE_ACCOUNT_TIME_CARDS_MODE"));
  var price=config.getMain().getPricePerMillion();
  // UTF-8 byte count conservatively bounds input tokens, with message overhead reserved.
  ObjectNode focus=ReflectionTestUtils.invokeMethod(archive,"reviewFocus",facts,draft);
  long inputBound=StoryArchiveService.CONTRACT.getBytes(StandardCharsets.UTF_8).length+input.toString().getBytes(StandardCharsets.UTF_8).length+Objects.toString(focus).getBytes(StandardCharsets.UTF_8).length+1024;
  double reserve=inputBound*price.getPrompt()/1_000_000d+8192*price.getCompletion()/1_000_000d;
  BigDecimal before=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.06830"));
  if(!replay)assertTrue(before.add(BigDecimal.valueOf(reserve)).compareTo(new BigDecimal("2"))<=0,"Worst-case call allowance would exceed the remaining two-yuan budget");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM llm_operation_usage WHERE session_id=? AND usage_known=0",Integer.class,sid));
  var request=json.readTree(dir.resolve("step7-request.json").toFile());var result=json.readTree(dir.resolve("step7-result.json").toFile());String op=request.path("operationId").asText();long epoch=request.path("branchEpoch").asLong(),log=result.path("logId").asLong();
  long calls=((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue();
  var proseBefore=jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid);
  boolean focused=replay || "RECONCILE".equals(System.getenv("LIVE_ACCOUNT_TIME_CARDS_MODE"));ObjectNode projected;
  try(var scope=LLMUsage.observe(usage.sink(sid,epoch,op,log,calls+1))){
   if(focused){
    StoryArchiveService service=archive;
    if(replay){String paidRaw=Files.readString(dir.resolve("account-time-cards-raw-attempt5.json"));var replayModel=org.mockito.Mockito.mock(LLMProvider.class);org.mockito.Mockito.when(replayModel.chatForJson(org.mockito.ArgumentMatchers.anyList())).thenReturn(new LLMResponse(paidRaw,paidRaw,0,0,0,0));service=new StoryArchiveService(replayModel);}
    projected=service.reconcileAccounts(facts,draft,(ObjectNode)json.readTree(dir.resolve("account-time-cards-projection-attempt3.json").toFile()));Files.writeString(dir.resolve("account-time-cards-raw.json"),projected.at("/accountReview/rawResponse").asText());}
   else projected=archive.project(facts,draft,"",raw->{try{Files.writeString(dir.resolve("account-time-cards-raw.json"),raw);}catch(Exception failure){throw new IllegalStateException(failure);}});
  }
  Files.writeString(dir.resolve("account-time-cards-projection.json"),projected.toPrettyString());
  var merged=archive.merge(draft,projected,facts);Files.writeString(dir.resolve("account-time-cards-merged.json"),merged.toPrettyString());
  BigDecimal after=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.06830"));
  var receipt=json.createObjectNode().put("beforeUpperCny",before).put("afterUpperCny",after).put("costCny",after.subtract(before)).put("maximumReservedCny",replay?0:reserve).put("paidCalls",replay?0:1).put("replayedActualPaidResponse",replay).put("oldElapsedSeconds",saved.path("endSeconds").asLong()-saved.path("startSeconds").asLong()).put("reviewedElapsedSeconds",merged.path("elapsedSeconds").asLong());
  Files.writeString(dir.resolve("account-time-cards-live-checks.json"),receipt.toPrettyString());
  assertTrue(after.compareTo(new BigDecimal("2"))<=0);assertEquals(calls+(replay?0:1),((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue());
  assertEquals(draft.path("story"),merged.path("story"));long elapsed=merged.path("elapsedSeconds").asLong();assertTrue(elapsed>=14*86400L && elapsed<=17*86400L,"Count only the remainder of the working month and the following day");
  var expected=new HashSet<String>();for(var card:facts.at("/plotMemory/playerKnownState")){if(card.path("eventView").isObject())expected.add(card.path("eventId").asText());for(var thread:card.path("foreshadows"))expected.add(thread.path("threadId").asText());}
  var reviewed=new HashSet<String>();int changed=0;for(var review:projected.path("knownPlotReviews")){reviewed.add(review.path("threadId").asText(review.path("eventId").asText()));if(review.path("changed").asBoolean())changed++;}
  assertTrue(reviewed.containsAll(expected),"Every supplied public event and thread must be reviewed");assertTrue(changed>0,"Known old results should refresh stale cards");
  boolean conflict=false;for(var accountState:projected.at("/continuityState/accounts"))if(!accountState.path("unresolved").isEmpty())conflict=true;assertTrue(conflict,"Contradictory received/paid accounts must remain explicitly unresolved");
  boolean hasReceipt=false,hasPayment=false,misclassified=false;for(var accountState:projected.at("/continuityState/accounts")){
   for(var row:accountState.path("receipts")){if(row.path("from").asText().contains("德昌") && row.path("to").asText().contains("同福"))hasReceipt=true;if(row.path("from").asText().contains("同福") && row.path("to").asText().contains("刘"))misclassified=true;}
   for(var row:accountState.path("payments"))if(row.path("from").asText().contains("同福") && row.path("to").asText().contains("刘"))hasPayment=true;
  }assertTrue(hasReceipt && hasPayment && !misclassified,"The concrete old-case receipt and carpenter payment must be classified from the same shop's perspective");
  if(focused){
   assertEquals("DONE",projected.at("/accountReview/status").asText());var reviewedRaw=archive.normalize(projected.at("/accountReview/rawResponse").asText());var direct=new HashSet<String>();
   for(var review:reviewedRaw.path("knownPlotReviews")){
    String id=review.path("threadId").asText(review.path("eventId").asText());direct.add(id);
    if(id.equals("fo_aa8deb05734f39f7b378be51") || id.equals("evt_9538ea80a8e838d7ab4ce6f0")){String summary=review.at("/playerView/summary").asText();assertTrue(review.path("changed").asBoolean());assertTrue(summary.contains("收") && (summary.contains("矛盾") || summary.contains("冲突") || summary.contains("不一致")),"Both the event and its thread must retain the concrete receipt contradiction");}
    if(id.equals("evt_3711ccb5b08931e9bfdca570")){assertTrue(review.path("changed").asBoolean());assertNotEquals("待东家定章程",review.at("/playerView/status").asText());}
   }
   assertTrue(direct.containsAll(expected),"Focused response itself must cover the flattened review list");String summary=projected.path("continuity").asText();assertTrue(summary.contains("矛盾") || summary.contains("冲突") || summary.contains("不一致"),"The cumulative memory must not retain the old fully-settled conclusion");
  }
  String probe="account_time_probe_"+UUID.randomUUID().toString().substring(0,8);
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   jdbc.update("INSERT INTO story_plot_event SELECT ?,event_id,title,category,status,record_json,player_view,source_log_id,source_frame,updated_seconds,round_no FROM story_plot_event WHERE session_id=?",probe,sid);
   jdbc.update("INSERT INTO story_foreshadow SELECT ?,thread_id,event_id,title,status,record_json,player_view,source_log_id,source_frame,updated_seconds,round_no FROM story_foreshadow WHERE session_id=?",probe,sid);
   for(var old:facts.at("/plotMemory/playerKnownState")){
    jdbc.update("UPDATE story_plot_event SET player_view=? WHERE session_id=? AND event_id=?",old.path("eventView").toString(),probe,old.path("eventId").asText());
    for(var thread:old.path("foreshadows")){var view=(ObjectNode)thread.deepCopy();view.remove("threadId");jdbc.update("UPDATE story_foreshadow SET player_view=? WHERE session_id=? AND thread_id=?",view.toString(),probe,thread.path("threadId").asText());}
   }
   var reviewOnly=json.createObjectNode();reviewOnly.set("knownPlotReviews",projected.path("knownPlotReviews").deepCopy());var normalized=archive.normalize(reviewOnly.toString());assertTrue(plots.apply(probe,log,0,6,saved.path("startSeconds").asLong()+elapsed,normalized.path("plotUpdates")).isEmpty());
   continuity.apply(probe,facts.path("playerId").asText(),saved.path("sceneId").asText(),6,log,0,merged.path("continuityState"),merged.path("timeProgress"));assertFalse(continuity.read(probe,facts.path("playerId").asText()).path("accounts").isEmpty());
   try{Files.writeString(dir.resolve("account-time-cards-probe-state.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("plots",plots.browse(probe,false,"","","",0,0),"continuityState",continuity.read(probe,facts.path("playerId").asText()))));}catch(Exception failure){throw new IllegalStateException(failure);}
   tx.setRollbackOnly();
  });
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event WHERE session_id=?",Integer.class,probe));
  assertEquals(proseBefore,jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid));
  receipt.put("allKnownCardsReviewed",true).put("changedCards",changed).put("conflictsRetained",true).put("directionsClassifiedCorrectly",true).put("focusedSourceReconciliation",focused).put("probePersistencePassed",true).put("probeRolledBack",true).put("originalProseUnchanged",true);
  Files.writeString(dir.resolve("account-time-cards-live-checks.json"),receipt.toPrettyString());
 }
}
