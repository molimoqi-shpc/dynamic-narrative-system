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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit, one-call maintenance probe. Disabled in normal tests; restricted to its test account. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@EnabledIfEnvironmentVariable(named="LIVE_CONTINUOUS_KNOWN_PLOTS",matches="yes")
class KnownPlotBackfillLiveIT {
 @Autowired LLMProvider model;@Autowired JdbcTemplate jdbc;@Autowired OperationUsageService usage;
 @Autowired StoryArchiveService archives;@Autowired StoryPlotService plots;@Autowired SessionStateService checkpoints;
 @Autowired PlatformTransactionManager transactionManager;
 final ObjectMapper json=new ObjectMapper();
 @Test void reconcileOnlyExistingPublicCardsFromPublishedSources()throws Exception{
  Path dir=Path.of(System.getenv("LIVE_CONTINUOUS_DIR"));var account=json.readTree(dir.resolve("account.json").toFile());
  String sid=account.path("sessionId").asText(),pid=account.path("playerId").asText();
  assertTrue(account.path("username").asText().startsWith("continuousqa_"));
  assertEquals(account.path("username").asText(),jdbc.queryForObject("SELECT username FROM player_profile WHERE player_id=? AND active_session_id=?",String.class,pid,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_runtime WHERE session_id=? AND active_operation IS NOT NULL",Integer.class,sid));
  var request=json.readTree(dir.resolve("step7-request.json").toFile());var result=json.readTree(dir.resolve("step7-result.json").toFile());
  String op=request.path("operationId").asText();long epoch=request.path("branchEpoch").asLong(),log=result.path("logId").asLong();
  assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM game_operation WHERE session_id=? AND branch_epoch=? AND operation_id=?",String.class,sid,epoch,op));
  assertTrue(jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",java.math.BigDecimal.class,sid).compareTo(new java.math.BigDecimal("1.80"))<0);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM llm_operation_usage WHERE session_id=? AND usage_known=0",Integer.class,sid));
  var before=jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid);
  var input=json.createObjectNode();input.set("savedPublicCards",json.valueToTree(plots.browse(sid,false,"","","",0,0)).path("events"));
  var published=input.putArray("publishedPlayerExperience");
  for(int n=1;n<=3;n++)published.addObject().put("round",n).put("story",Files.readString(dir.resolve("step"+n+"-story.txt")));
  input.put("task","仅更新这几张既有卡的玩家可见摘要与状态，使其反映玩家已经亲历的说明和章程。正文中若收付口径仍矛盾，就写明听过的说法与疑点，不假称客观全解。章程已经提出且实际执行，不再保留尚未说出。不要把后续新生活并入旧查账，不新增编号、不改故事、不创造新事实，不读取任何幕后记录。");
  Files.writeString(dir.resolve("public-card-backfill-input.json"),input.toPrettyString());
  String raw;long ceiling=((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue()+1;
  try(var scope=LLMUsage.observe(usage.sink(sid,epoch,op,log,ceiling))){
   var reply=LLMRequestPolicy.within(System.nanoTime()+65_000_000_000L,()->LLMRequestPolicy.ACTION.call(()->model.chatForJson(List.of(
    Map.of("role","system","content","你负责整理玩家已经实际获知的旧剧情卡，只根据给定已发表原文更新既有卡。返回JSON：{playerViewUpdates:{events:[{eventId,playerView:{title,summary,status,category}}],foreshadows:[{threadId,playerView:{title,summary,status}}]}}。保留说法来源和矛盾，不能把人物解释升级为事实，不输出小说或新剧情；两类卡分别检查，旧手续已有交代则更新其状态。"),
    Map.of("role","user","content",input.toString())))));
   raw=Objects.toString(reply.parsedJson()!=null?reply.parsedJson():reply.content(),"");
  }
  Files.writeString(dir.resolve("public-card-backfill-raw.json"),raw);
  var candidate=(ObjectNode)json.readTree(raw);candidate.putArray("endingCast");var normalized=archives.normalize(candidate.toString());
  var allowedEvents=new HashSet<String>();var allowedThreads=new HashSet<String>();
  for(var card:input.path("savedPublicCards")){allowedEvents.add(card.path("eventId").asText());for(var thread:card.path("foreshadows"))allowedThreads.add(thread.path("threadId").asText());}
  for(var e:normalized.at("/plotUpdates/events"))assertTrue(allowedEvents.contains(e.path("eventId").asText()));
  for(var t:normalized.at("/plotUpdates/foreshadows"))assertTrue(allowedThreads.contains(t.path("threadId").asText()));
  long seconds=jdbc.queryForObject("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid);
  new TransactionTemplate(transactionManager).executeWithoutResult(tx->{assertTrue(plots.apply(sid,log,0,7,seconds,normalized.path("plotUpdates")).isEmpty());checkpoints.refreshCurrent(sid,pid);});
  assertEquals(before,jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid));
  Files.writeString(dir.resolve("public-card-backfill-after.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(plots.browse(sid,false,"","","",0,0)));
 }
}
