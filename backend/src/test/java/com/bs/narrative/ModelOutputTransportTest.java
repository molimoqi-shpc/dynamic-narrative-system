package com.bs.narrative;

import com.bs.narrative.llm.ModelOutput;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelOutputTransportTest {
 @RepeatedTest(3) void theRealArchiveReplyPreservesQuotedEvidenceAndAllResultTables()throws Exception{
  String raw=new String(getClass().getResourceAsStream("/fixtures/archive-bare-quotes.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw));
  var root=ModelOutput.object(raw);assertTrue(root.path("continuityState").isObject());assertTrue(root.path("roleExperiences").isArray());assertTrue(root.at("/continuityState/narrativeState/completed").size()>=3);
  assertTrue(root.toString().contains("封记有损，请行会验看。"));assertTrue(root.toString().contains("我不管，也不担保。"));
 }
 @Test void embeddedClosingQuotesAndTrailingCommasAreTransportOnly(){
  var root=ModelOutput.object("{\"basis\":\"他声明：\"我不担保。\"\",\"items\":[1,],}");
  assertEquals("他声明：\"我不担保。\"",root.path("basis").asText());assertEquals(1,root.path("items").size());
 }
 @Test void missingContentOrASecondDocumentStillCannotBeAccepted(){
  for(String raw:java.util.List.of("{\"basis\":\"未写完","{\"basis\":\"好\" \"next\":1}","{} {}","{\"items\":[{}"))assertThrows(IllegalArgumentException.class,()->ModelOutput.object(raw));
 }
}
