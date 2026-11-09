package com.bs.narrative.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryPassageProgressTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void oldCandidateProgressIsNeverPromotedWithoutAPublishedSource(){
  var operation=json.createObjectNode();operation.putObject("sceneAuthorPlan").put("currentProgress","未发表候选中已经追船并值夜");
  var facts=json.createObjectNode();facts.putObject("episodePlan").put("text","旧冻结候选");
  StoryPassageProgress.attachPlan(facts,operation);assertFalse(facts.has("episodePlan"));
 }
 @Test void onlyTheAcceptedPassageSuppliesProgressAndPlan(){
  var operation=json.createObjectNode().put("logId",101);operation.putArray("scenePassages").add("已抵闸并稳住水头，仍是午后");
  operation.putObject("sceneAuthorPlan").put("currentProgress","候选已追船并值夜");
  var published=json.createObjectNode();var memory=published.putObject("continuityState").putObject("narrativeState");
  memory.putArray("completed").add("已抵闸并稳住水头");memory.put("currentSituation","午后仍在闸口");memory.putArray("open").add("货船与闸的关联不明");
  published.putObject("authorPlan").put("centralQuestion","留下疑点后先确认家人平安");
  StoryPassageProgress.commit(operation,published,2);
  var facts=json.createObjectNode();facts.set("alreadyHappenedThisChapter",operation.path("scenePassages"));facts.set("committedPassageResults",operation.path("scenePassageResults"));
  StoryPassageProgress.attachPlan(facts,operation);var last=StoryAuthorContext.brief(facts);
  assertEquals("CONTINUING_AFTER_COMMITTED",last.at("/episodeFocus/intentStatus").asText());
  assertEquals("已抵闸并稳住水头",last.at("/continuation/committedTurnProgress/0/completed/0").asText());
  assertEquals(2,last.at("/continuation/committedTurnProgress/0/source/frame").asInt());
  assertEquals("PUBLISHED_SOURCE_AUTHOR_PLAN",facts.at("/episodePlan/status").asText());assertFalse(facts.toString().contains("已追船并值夜"));
  assertFalse(operation.has("sceneAuthorPlan"));assertFalse(published.has("source"));
 }
 @Test void aLaterPassageWithNoPlanCannotResurrectAnEarlierCandidate(){
  var operation=json.createObjectNode().put("logId",101);operation.putArray("scenePassages").add("第一段已发表");
  var first=json.createObjectNode();first.putObject("authorPlan").put("currentProgress","第一段已发表");StoryPassageProgress.commit(operation,first,0);
  operation.withArray("scenePassages").add("第二段只写新增后果");StoryPassageProgress.commit(operation,json.createObjectNode(),1);
  var facts=json.createObjectNode();StoryPassageProgress.attachPlan(facts,operation);assertFalse(facts.has("episodePlan"));assertEquals(2,operation.path("scenePassageResults").size());
 }
 @Test void legacyPassagesKeepExactSourcesWithoutInventedCompletion(){
  var facts=json.createObjectNode().put("playerDirection","查看水情");facts.putArray("alreadyHappenedThisChapter").add("已经到闸口");
  var progress=StoryContinuationContext.committedTurnProgress(facts);assertEquals("/alreadyHappenedThisChapter/0",progress.get(0).path("publishedSourceRef").asText());assertFalse(progress.get(0).has("completed"));
  facts.withArray("alreadyHappenedThisChapter").removeAll();assertEquals("REQUESTED_NOT_OCCURRED",StoryAuthorContext.brief(facts).at("/episodeFocus/intentStatus").asText());
 }
}
