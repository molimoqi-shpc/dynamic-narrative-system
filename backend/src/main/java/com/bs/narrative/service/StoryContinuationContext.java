package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Source placement only. Never interprets prose, advances time or grants character knowledge. */
final class StoryContinuationContext {
 private static final ObjectMapper JSON=new ObjectMapper();
 private StoryContinuationContext() {}

 static boolean hasCommittedPassages(ObjectNode facts){
  return facts.path("alreadyHappenedThisChapter").isArray() && !facts.path("alreadyHappenedThisChapter").isEmpty();
 }

 /** Small committed records from the same archive call, with exact prose pointers for old operations. */
 static ArrayNode committedTurnProgress(ObjectNode facts){
  var out=JSON.createArrayNode();var passages=facts.path("alreadyHappenedThisChapter");
  for(int i=0;i<passages.size();i++){
   var entry=out.addObject().put("passageIndex",i).put("publishedSourceRef","/alreadyHappenedThisChapter/"+i);
   for(var saved:facts.path("committedPassageResults"))if(saved.path("passageIndex").asInt(-1)==i){
    // Copy archived results only. Candidate plans are never a source of completed work.
    for(String field:List.of("source","completed","currentSituation","open","actualTime","timeProgress"))if(saved.has(field))entry.set(field,saved.path(field).deepCopy());
    break;
   }
  }
  return out;
 }

 static void separateOpening(ObjectNode input){
  historicalOpening(input.path("currentWorld"));
  for(var destination:input.path("routeDestinations"))historicalOpening(destination);
 }
 private static void historicalOpening(JsonNode value){
  if(value instanceof ObjectNode object && object.has("openingEnvironment")){
   var opening=object.putObject("historicalOpening");opening.set("text",object.remove("openingEnvironment"));
   opening.put("meaning","该场景最初建立时的描写，仅供背景参考；其中时刻、人物站位、尚待处理的事情都不是当前现场。当前从最新已发表正文接续。");
  }
 }

 static ObjectNode startSource(ObjectNode facts){
  var out=JSON.createObjectNode();var latest=StoryAuthorContext.latestPublishedPassage(facts);
  if(!latest.isTextual() || latest.asText().isBlank())return out;
  var committed=facts.path("alreadyHappenedThisChapter");var chapters=facts.path("previousChapters");
  if(!committed.isEmpty())out.put("sourceRef","/alreadyHappenedThisChapter/"+(committed.size()-1));
  else if(!chapters.isEmpty()){
   var chapter=chapters.get(chapters.size()-1);out.put("sourceRef","/previousChapters/"+(chapters.size()-1)+"/story");
   for(String field:List.of("logId","round","recordedEndSeconds"))if(chapter.has(field))out.set(field,chapter.path(field).deepCopy());
  }
  String[] paragraphs=latest.asText().split("\\R\\s*\\R");
  out.put("publishedEnding",String.join("\n\n",Arrays.copyOfRange(paragraphs,Math.max(0,paragraphs.length-5),paragraphs.length)));
  out.put("meaning","本篇起点就是这段已发表原文的实际末尾；原文中的回忆、前面经过的日子不重新发生。此处只绑定来源，不替小说推断日期。");
  var timing=publishedEndpoint(facts);if(!timing.isEmpty())out.set("publishedEndpoint",timing);
  return out;
 }

 /** Carries the accepted prior endpoint, without interpreting a word of the story
  * or exposing this candidate's provisional duration as an answer to the reader. */
 static ObjectNode publishedEndpoint(ObjectNode facts){
  var out=JSON.createObjectNode();var committed=facts.path("alreadyHappenedThisChapter");var chapters=facts.path("previousChapters");
  JsonNode time=JSON.createObjectNode();String ref="";
  if(!committed.isEmpty()){
   int last=committed.size()-1;
   for(var saved:facts.path("committedPassageResults"))if(saved.path("passageIndex").asInt(-1)==last){
    time=saved.path("timeProgress").path("to").isTextual()?saved.path("timeProgress"):saved.path("actualTime");
    ref="/alreadyHappenedThisChapter/"+last;break;
   }
  }else if(!chapters.isEmpty()){
   var chapter=chapters.get(chapters.size()-1);time=chapter.path("timeProgress");ref="/previousChapters/"+(chapters.size()-1)+"/story";
   var memory=facts.at("/continuityState/narrativeState");
   if(!time.path("to").isTextual() && sameLog(chapter.path("logId"),memory.at("/source/logId")))time=memory.path("actualTime");
  }
  if(!time.path("to").isTextual() || time.path("to").asText().isBlank())return out;
  out.put("sourceRef",ref);for(String field:List.of("to","dayNumbering","basis"))if(time.has(field))out.set(field,time.path(field).deepCopy());
  if(StoryDuration.calendarSeconds(time.path("calendarInterval")).isPresent()){
   var clock=out.putObject("clockAtEnd");for(String field:List.of("hour","minute"))if(time.at("/calendarInterval/to").has(field))clock.set(field,time.at("/calendarInterval/to").path(field).deepCopy());
  }
  out.put("meaning","同一已发表正文保存的实际终点，是本篇0点的时段依据，不是本篇预计耗时。先结合这个终点和原文确认上午/午后/夜里，再计算此后新增经过；普通日间被概述省略仍消耗时间，不能只算夜里到早晨。较旧摘要不覆盖此处。");
  return out;
 }
 private static boolean sameLog(JsonNode left,JsonNode right){return left.isIntegralNumber() && right.isIntegralNumber() && left.asLong()==right.asLong();}

 static ObjectNode handoff(ObjectNode facts){
  var out=JSON.createObjectNode().put("purpose","直接从以下原文停点续写；背景库中的旧开场、旧日序与过去计划不重新发生。");
  var chapters=facts.path("previousChapters");var committed=facts.path("alreadyHappenedThisChapter");
  var latest=chapters.isArray() && !chapters.isEmpty()?chapters.get(chapters.size()-1):JSON.createObjectNode();
  String ref=!committed.isEmpty()?"/alreadyHappenedThisChapter/"+(committed.size()-1):!latest.isEmpty()?"/previousChapters/"+(chapters.size()-1)+"/story":"";
  out.put("publishedSourceRef",ref);
  // Exact prose, not another generated synopsis or a clipped final sentence.
  out.set("latestPublishedPassage",StoryAuthorContext.latestPublishedPassage(facts));
  var endpoint=publishedEndpoint(facts);if(!endpoint.isEmpty())out.set("publishedEndpoint",endpoint);
  out.set("committedTurnProgress",committedTurnProgress(facts));
  out.put("progressMeaning","committedTurnProgress只列本轮已发表段落及其同次归档结果；每项publishedSourceRef可直接查原文。没有归档结果的旧段落以原文为准，不拿候选构思补成事实。completed已经兑现，open只是尚未解决的影响，不是每项都要在本轮完成的新指令。");
  var status=out.putObject("publishedStatus");status.put("committedPassagesThisTurn",committed.size());
  if(committed.isEmpty())for(String field:List.of("logId","round","recordedStartSeconds","recordedEndSeconds","timeProgress"))
   if(latest.has(field))status.set(field,latest.path(field).deepCopy());
  status.put("meaning","本篇只增加上述原文末尾之后的新经过；本轮已发布过片段时，玩家原方向尚未实现的部分继续，不从头再做。");
  var memory=facts.at("/continuityState/narrativeState");
  if(memory.isObject()){
   out.set("rememberedResults",memory.deepCopy());
   boolean same=committed.isEmpty() && sameLog(latest.path("logId"),memory.at("/source/logId"));
   out.put("memoryMatchesLatestChapter",same);
  }
  out.put("memoryMeaning","rememberedResults是归档摘要，非新一份原文。只承接确有出处的已兑现结果与仍有效影响；即便出处是最新章，摘要中的日序和归属也须对照上面的原文。摘要较旧或与原文矛盾时，不把旧处境覆盖到当前，更不安排人物再做一次以补档。");
  out.set("playerDirection",facts.path("playerDirection").deepCopy());
  if(facts.has("decisionContext"))out.set("decisionContext",facts.path("decisionContext").deepCopy());
  out.put("composition","先在同次创作中理清起点、经过和终点，写完整故事，再给这份正文的新增耗时。跨日概述要说明覆盖范围，随后展开其中某日时不让读者误解为又过几日后倒回；普通无事发生的日子可以省略。沿用本轮玩家方向、实际角色意愿和已发表结果，原方向内自然推进到实质结果或具体阻碍，不为添余韵无故延长，不为追逐天数删掉真实后果。定稿后按实际正文保存同一终点，不把时间解释当成另一次剧情来源。不输出自查报告，无额外审稿调用。");
  return out;
 }
}
