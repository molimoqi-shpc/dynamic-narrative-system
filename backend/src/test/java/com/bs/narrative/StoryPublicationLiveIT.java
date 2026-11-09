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
import java.nio.file.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** One opt-in paid source replay; the edited manuscript is written only to an evidence file. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","llm.max-retries=0"})
@EnabledIfEnvironmentVariable(named="LIVE_PUBLICATION_REPLAY",matches="yes")
class StoryPublicationLiveIT {
 @Autowired StoryArchiveService archive;@Autowired JdbcTemplate jdbc;@Autowired OperationUsageService usage;@Autowired LLMConfig config;
 @Test void replayTheFailedUnpublishedManuscriptWithoutChangingAnyPublishedStory()throws Exception{
  int step=Integer.parseInt(java.util.Objects.toString(System.getenv("LIVE_PUBLICATION_STEP"),"15"));
  String label="final-manuscript-step"+step+java.util.Objects.toString(System.getenv("LIVE_PUBLICATION_ATTEMPT"),"");
  var json=new ObjectMapper();Path dir=Path.of(System.getenv("LIVE_PUBLICATION_DIR")),output=dir.resolve(label+"-checks.json");
  assertFalse(Files.exists(output),"Do not repeat this paid replay");
  var saved=json.readTree(dir.resolve(step==15?"step15-before-publication-fix-payload.json":"step"+step+"-payload.json").toFile());var request=json.readTree(dir.resolve("step"+step+"-request.json").toFile());
  String sid=request.path("sessionId").asText(),op=request.path("operationId").asText();long epoch=request.path("branchEpoch").asLong(),log=saved.path("logId").asLong();
  var facts=(ObjectNode)saved.path("sceneFrameFacts").deepCopy();facts.put("allowPublicationEdits",true);var draft=(ObjectNode)saved.path("sceneDraft").deepCopy();
  var stories=jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid);
  var runtime=jdbc.queryForMap("SELECT story_seconds,revision,active_operation FROM game_runtime WHERE session_id=?",sid);assertNull(runtime.get("active_operation"));
  BigDecimal before=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.03115"));
  var input=archive.input(facts,draft);var price=config.getMain().getPricePerMillion();
  long bytes=input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+StoryArchiveService.CONTRACT.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+16000;
  double reserve=bytes*price.getPrompt()/1_000_000d+12288*price.getCompletion()/1_000_000d;
  assertTrue(before.add(BigDecimal.valueOf(reserve)).compareTo(new BigDecimal("4"))<0);
  Files.writeString(output,"{\"status\":\"STARTED\"}");long calls=((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue();ObjectNode projected;
  try(var observed=LLMUsage.observe(usage.sink(sid,epoch,op,log,calls+1))){
   projected=archive.project(facts,draft,"",raw->{try{Files.writeString(dir.resolve(label+"-raw.json"),raw);}catch(Exception e){throw new IllegalStateException(e);}});
  }
  var merged=archive.merge(draft,projected,facts);var evidence=json.createObjectNode();evidence.set("originalDraft",draft);evidence.set("projection",projected);evidence.set("merged",merged);evidence.put("readOnlyStoryReplay",true);evidence.put("beforeBatchUpperCny",before);
  BigDecimal after=jdbc.queryForObject("SELECT COALESCE(SUM(cost_cny),0) FROM llm_operation_usage WHERE session_id=?",BigDecimal.class,sid).add(new BigDecimal("0.03115"));
  evidence.put("afterBatchUpperCny",after).put("costCny",after.subtract(before));Files.writeString(output,evidence.toPrettyString());
  assertEquals(stories,jdbc.queryForList("SELECT log_id,processed_dialog FROM interaction_log WHERE session_id=? ORDER BY log_id",sid));assertEquals(runtime,jdbc.queryForMap("SELECT story_seconds,revision,active_operation FROM game_runtime WHERE session_id=?",sid));
  assertEquals(calls+1,((Number)usage.forOperation(sid,epoch,op).get("calls")).longValue());
  assertTrue(projected.at("/publication/unchanged").asBoolean() || !projected.at("/publication/story").asText().isBlank());
  assertEquals("FINAL_PUBLICATION",merged.at("/timeProgress/version").asText());
  assertEquals(projected.at("/timeProgress/elapsedSeconds").asLong(),merged.path("elapsedSeconds").asLong());
  assertEquals(projected.at("/publication/timeProgress/elapsedParts"),merged.at("/timeProgress/elapsedParts"));
  Files.writeString(dir.resolve(label+"-story.txt"),merged.path("story").asText());
 }
}
