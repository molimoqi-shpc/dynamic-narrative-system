package com.bs.narrative.service;

import com.bs.narrative.llm.ModelOutput;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleDirectionJsonTest {
 @RepeatedTest(3) void completeRealRoleReplyWithOneSurplusRootCloserKeepsEveryField()throws Exception{
  String raw=new String(getClass().getResourceAsStream("/role-extra-root-closer-20261029.txt").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThrows(IllegalArgumentException.class,()->ModelOutput.object(raw));
  assertEquals(ModelOutput.object(raw.substring(0,raw.length()-1)),RoleDirectionJson.parse(raw));
  assertFalse(RoleDirectionJson.parse(raw).path("continuity").isMissingNode());
  assertThrows(IllegalArgumentException.class,()->RoleDirectionJson.parse("{} } {\"intention\":\"another candidate\"}"));
 }
 @RepeatedTest(3) void savedRealReplyWithOnlyItsRootDelimiterMissingIsRecoveredWithoutRewriting()throws Exception{
  String raw=new String(getClass().getResourceAsStream("/fixtures/role-direction-missing-root.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThrows(IllegalArgumentException.class,()->ModelOutput.object(raw));
  var restored=RoleDirectionJson.parse(raw);
  assertEquals(ModelOutput.object(raw+"}"),restored);
  assertEquals("以上均为意愿，浅滩情形、她是否答应、救援结果均未发生。",restored.at("/characterTemplate/actionPlan/notYetHappened").asText());
 }
 @Test void missingContentStringsNestedObjectsAndSeparateDocumentsAreStillRejected(){
  for(String raw:java.util.List.of("{\"thought\":\"unfinished","{\"thought\":\"ok\",\"characterTemplate\":{\"plan\":{}","{\"thought\":\"ok\",\"items\":[{}","{\"thought\":\"ok\",","{\"thought\":\"ok\" \"intention\":{}}","{} {\"thought\":\"ok\""))
   assertThrows(IllegalArgumentException.class,()->RoleDirectionJson.parse(raw),raw);
 }
 @RepeatedTest(3) void realEmbeddedQuotationMarksRemainLiteralProseWithoutAnotherModelCall()throws Exception{
  String raw=new String(getClass().getResourceAsStream("/fixtures/role-direction-bare-quotes.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw));var restored=RoleDirectionJson.parse(raw);
  assertEquals("爱用\"咱们\"称呼驿舍的事",restored.at("/characterTemplate/expression/wordingHabits/1").asText());
  assertEquals(raw.replace("爱用\"咱们\"称呼驿舍的事","爱用\\\"咱们\\\"称呼驿舍的事"),restored.toString());
 }
}
