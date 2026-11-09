package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** One small paid reading of the actual failed clock; never edits the published save. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","llm.max-retries=0"})
@EnabledIfEnvironmentVariable(named="LIVE_SOURCE_CLOCK",matches="yes")
class StorySourceClockLiveIT {
 @Autowired LLMProvider model;@Autowired OperationUsageService usage;@Autowired JdbcTemplate jdbc;@Autowired StoryArchiveService archive;
 @Test void readTheActualMultiDayManuscriptWithoutItsOldNumbers()throws Exception{
  int step=Integer.parseInt(java.util.Objects.toString(System.getenv("LIVE_SOURCE_CLOCK_STEP"),"21"));
  String label="source-clock-step"+step+java.util.Objects.toString(System.getenv("LIVE_SOURCE_CLOCK_ATTEMPT"),"");
  var json=new ObjectMapper();Path dir=Path.of(System.getenv("LIVE_PUBLICATION_DIR")),output=dir.resolve(label+"-checks.json");assertFalse(Files.exists(output));
  var payload=json.readTree(dir.resolve("step"+step+"-payload.json").toFile());var request=json.readTree(dir.resolve("step"+step+"-request.json").toFile());
  String sid=request.path("sessionId").asText(),op=request.path("operationId").asText();long epoch=request.path("branchEpoch").asLong(),log=payload.path("logId").asLong();
  var runtime=jdbc.queryForMap("SELECT story_seconds,revision,active_operation FROM game_runtime WHERE session_id=?",sid);assertNull(runtime.get("active_operation"));
  var stories=jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid);
  BigDecimal before=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.03115"));assertTrue(before.add(new BigDecimal("0.05")).compareTo(new BigDecimal("5"))<0);
  Files.writeString(output,"{\"status\":\"STARTED\"}");long calls=((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue();
  boolean finishing="yes".equals(System.getenv("LIVE_SOURCE_CLOCK_FINALIZE"));
  var facts=(ObjectNode)payload.path("sceneFrameFacts").deepCopy();facts.put("allowPublicationEdits",true).put("finalizeManuscript",finishing);var draft=(ObjectNode)payload.path("sceneDraft").deepCopy();if(finishing)draft.set("story",payload.path("story").deepCopy());ObjectNode result;
  try(var observe=LLMUsage.observe(usage.sink(sid,epoch,op,log,calls+1))){
   result=new StorySourceClock(model).read(facts,draft,(ObjectNode)payload.path("sceneArchiveResult"),raw->{try{Files.writeString(dir.resolve(label+"-raw-cache.json"),raw);}catch(Exception e){throw new RuntimeException(e);}});
  }
  var merged=archive.merge(draft,result,facts);BigDecimal after=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.03115"));
  var evidence=json.createObjectNode().put("readOnly",true).put("beforeBatchUpperCny",before).put("afterBatchUpperCny",after).put("costCny",after.subtract(before));evidence.set("result",result);evidence.set("merged",merged);Files.writeString(output,evidence.toPrettyString());
  assertEquals(runtime,jdbc.queryForMap("SELECT story_seconds,revision,active_operation FROM game_runtime WHERE session_id=?",sid));assertEquals(stories,jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid));
  if(!finishing){assertEquals(payload.path("story"),merged.path("story"));assertEquals(777600,merged.path("elapsedSeconds").asLong(),"From the prior evening through days five, six, seven, the later rainy day, and its following evening: nine days, not eight");}
  else{assertTrue(merged.path("elapsedSeconds").asLong()>0);Files.writeString(dir.resolve(label+"-story.txt"),merged.path("story").asText());}
  assertEquals(calls+1,((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue());
 }
}
