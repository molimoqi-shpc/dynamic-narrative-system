package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Carries only source-bound, committed author results. Never interprets story text. */
final class StoryPassageProgress {
 private StoryPassageProgress() {}

 static void commit(ObjectNode operation,ObjectNode published,int frame){
  int index=operation.path("scenePassages").size()-1;
  var record=operation.withArray("scenePassageResults").addObject().put("passageIndex",index);
  record.putObject("source").put("logId",operation.path("logId").asLong()).put("frame",frame);
  var memory=published.at("/continuityState/narrativeState");
  for(String field:List.of("completed","currentSituation","open","actualTime"))
   if(memory.has(field))record.set(field,memory.path(field).deepCopy());
  if(published.path("timeProgress").isObject())record.set("timeProgress",published.path("timeProgress").deepCopy());
  // Older operations may contain a plan from a discarded candidate. Never promote it.
  operation.remove(List.of("sceneAuthorPlan","committedAuthorPlan"));
  JsonNode plan=published.path("authorPlan");
  if(plan.isObject() && !plan.isEmpty() || plan.isTextual() && !plan.asText().isBlank()){
   var saved=operation.putObject("committedAuthorPlan").put("passageIndex",index);
   saved.set("source",record.path("source").deepCopy());saved.set("content",plan.deepCopy());
  }
 }

 static void attachPlan(ObjectNode facts,ObjectNode operation){
  facts.remove("episodePlan");
  var saved=operation.path("committedAuthorPlan");int index=saved.path("passageIndex").asInt(-1);
  if(index<0 || index>=operation.path("scenePassages").size() || !saved.has("content"))return;
  var plan=facts.putObject("episodePlan").put("status","PUBLISHED_SOURCE_AUTHOR_PLAN")
   .put("publishedSourceRef","/alreadyHappenedThisChapter/"+index)
   .put("meaning","这是对应已发表段落的作者备忘。已发生进展以该原文及committedPassageResults为准；构想中的未来仍未发生，不作为新一份历史。");
  plan.set("source",saved.path("source").deepCopy());plan.set("content",saved.path("content").deepCopy());
 }
}
