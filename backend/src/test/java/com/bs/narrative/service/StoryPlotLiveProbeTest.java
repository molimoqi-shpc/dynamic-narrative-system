package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: one paid archival reprojection of existing QA prose, without any game mutation. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@EnabledIfEnvironmentVariable(named="STORY_PLOT_LIVE_INPUT",matches=".+")
class StoryPlotLiveProbeTest {
 @Autowired StoryArchiveService archive;
 @Test void resolvedClueIsPaidOffEvenWhenTheEventHasAnOutstandingPlayerChoice()throws Exception{
  var json=new ObjectMapper();var saved=json.readTree(Files.readString(Path.of(System.getenv("STORY_PLOT_LIVE_INPUT"))));
  var facts=(ObjectNode)saved.path("sceneFrameFacts").deepCopy();var draft=json.createObjectNode().put("story",saved.path("story").asText());draft.set("authorPlan",saved.path("sceneAuthorPlan").deepCopy());draft.putArray("newCharacters");
  var evidence=json.createObjectNode().put("readOnly",true).put("fixedStory",draft.path("story").asText());var calls=evidence.putArray("calls");var attempts=new AtomicInteger();
  LLMUsage.begin();
  try(var observed=LLMUsage.observe(event->{
   if("STARTED".equals(event.state()) && attempts.incrementAndGet()>1)throw new LLMApiException("本次只允许一次归档调用，不自动重抽",false);
   calls.addObject().put("id",event.id()).put("state",event.state()).put("usageKnown",event.known());
  })){
   var projection=archive.project(facts,draft,null);evidence.set("projection",projection);evidence.set("usage",json.valueToTree(LLMUsage.totals()));
   Files.writeString(Path.of(System.getenv("STORY_PLOT_LIVE_OUTPUT")),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
   assertEquals(1,LLMUsage.totals().calls());String original=facts.at("/plotMemory/recentEvents/0/foreshadows/0/threadId").asText();boolean found=false;
   for(var thread:projection.at("/plotUpdates/foreshadows"))if(original.equals(thread.path("threadId").asText())){found=true;assertEquals("PAID_OFF",thread.path("status").asText());assertFalse(thread.path("payoff").asText().isBlank());}
   assertTrue(found,"Must update the existing clue instead of creating another event");
  }finally{LLMUsage.clear();}
 }
}
