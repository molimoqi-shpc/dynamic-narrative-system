package com.bs.narrative.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoryReplyEnvelopeTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void preservesAnAuthoredDecisionOutsideTheNovelWithoutAnotherModelCall()throws Exception{
  var original=json.createObjectNode().put("stop","DECISION").put("story","他把来意说明，等待你的答复。\n\ndecision:{\"type\":\"COMMITMENT\",\"question\":\"是否接受同行托付？\",\"stakes\":\"需要承担新的义务\",\"counterpartId\":\"a\"}");
  var out=StoryReplyEnvelope.normalize(original);
  assertEquals("他把来意说明，等待你的答复。",out.path("story").asText());assertEquals("是否接受同行托付？",out.at("/decision/question").asText());assertEquals("a",out.at("/decision/counterpartId").asText());
  assertTrue(original.path("story").asText().contains("decision:"));assertFalse(original.has("decision"));
 }
 @Test void neverGuessesAnIncompleteFooterOrTreatsAProseMentionAsProtocol(){
  for(String text:new String[]{"他在信上写下decision一词。","正文\n\ndecision:{\"question\":\"未完","正文\n\ndecision:{\"note\":\"这是信里的附言\"}"}){
   var original=json.createObjectNode().put("stop","DECISION").put("story",text);assertSame(original,StoryReplyEnvelope.normalize(original));
  }
 }
 @Test void retainsAnAlreadyStructuredDecisionWhenATrailingObjectDisagrees()throws Exception{
  var original=json.createObjectNode().put("stop","DECISION").put("story","正文\n\ndecision:{\"type\":\"ROMANCE\",\"question\":\"另一个问题\"}");
  original.set("decision",json.readTree("{\"type\":\"ALLIANCE\",\"question\":\"原问题\"}"));assertSame(original,StoryReplyEnvelope.normalize(original));
 }
}
