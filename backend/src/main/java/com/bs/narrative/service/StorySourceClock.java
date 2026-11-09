package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Cached main-author finalization; normal play reviews every manuscript before result-only archiving. */
final class StorySourceClock {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final LLMProvider model;
 StorySourceClock(LLMProvider model){this.model=model;}
 static final String PROMPT="""
  你是主API的正文时间记录阶段，只读已完成的最终稿，不续写、不改故事，不处理人物表、账目或剧情卡。
  actualStart是上一篇完整已发表正文，本篇起点是它的实际末尾，记作0；只记录此后新增经过。日期编号不是已过整日的数量，起点当天不额外加一天。
  按正文实际先后通读到最后一句，分清总起、回忆、概述内展开、真正又过的日子。第一日正午到次日清早通常不足24小时，不因跨了日历日期就添满一日；明确经过几整日则计入这些实际间隔。第五日等日期标签按各自明确起点、昼夜时段和实际经过理解，不能直接把5当成已满5日。不把篇末“这几日/七日”总结当作比实际经过更可靠的时钟。正常跳日不补流水账，同日场景不重复算一天。
  返回JSON {timeProgress:{scope:"THIS_PASSAGE",milestones:[{event:"少量实际转折",sourceQuote:"正文短句",elapsedPartsFromStart:[{amount:从本篇起点0累计到这个转折实际已过的非负数量,unit:"YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND"}]}],to:"正文真实最终停点",dayNumbering:"从上一篇末尾为0起算的实际时序",basis:"从正文说明各转折接续"}}。
  每个转折给从同一起点0算的累计位置，不是该场景又花了几天。同日各场景共享同一起点累计；早晚之间按实际小时推进，不能各加一天，也不在已有整日跨度外重复添上已经包含的当晚对话。最后一项必须是整篇真实结束处。程序只取最后累计位置换算，绝不将各转折的累计数相加。不要返回总计elapsedParts；不拿孤立日序代替实际经过，也不把各累计位置求和。模糊时段按正文合理估计，不编新事件，不取玩家预计几天或旧归档数量；它们未提供给你。
  """;
 static final String MANUSCRIPT_PROMPT="""
  你是唯一主API的正文定稿阶段。本次只完成一份可以直接发表的连贯正文及其实际时间，不填写角色表、世界书、物品、剧情卡或账目。
  actualStart是上一篇真正结束处，story是本篇尚未发表的稿。保留人物立场、已有重要事件和阶段结果、自然对白与文风；不另写新事件，不替玩家答应新的重大承诺，decisionBoundary必须保留。你可以修清前后冲突、删改普通余波中重复/多余的衔接，概述无事发生的日子，让全过程自然连贯。不是每一句都重写，更不是逐日流水账。
  从上一篇末尾接起，通读到最后一句：每次概述覆盖的时段、随后具体场景、当晚和次日必须连成一条线。概述又过数日后不能突然倒回更早的第几日；两次“往后几日”不能都塞进一个更短的篇末总结。遇到含混就明确这段概述的覆盖范围或自然改写，不靠读者勉强解释。日常无事的间隔可压缩，但保留已经发生的关键问答、出行、交付与后果。普通出门商量不制造重大抉择。处理过的事不重演，不凭空补未发生的回访。
  这次要交付读者可以自然连读的定稿，不是替原稿证明没有问题。原稿的日序编号和篇末总天数都可能写错；保留关键事件不等于必须保留错误日期。哪怕可以勉强解释成某种倒叙，只要普通读者会先读作又过几日，就应在最终正文把概述范围写清，让读者只有一种自然读法。总结不能抹平前面实际已经花去的时日或曾暂停的营生。
  起点是上一篇实际末尾为0，正文第一日是此后第一天。给少量重要转折从同一起点0算的累计位置：累计位置表达实际经过的时长。第几日只是场景日期，不等于已经过几整天；正午到同夜约半日内，正午到次日清早通常不足一日，不能把当日午后和当夜各算一日。明确过了几整日则保留，不因日期标签删减实际经过。先后时段按同一连续起点计算，不逐个日序求和。篇末旧“七天”标签若与真实经过冲突，直接修清正文与实际跨度，不迁就旧标签或某个预计天数。
  先给publication:{story:"有修订时给完整最终正文，保留所有未改段落；不返回局部替换"}；无需改文则publication:{unchanged:true}。
  再给timeProgress:{scope:"THIS_PASSAGE",milestones:[{event:"主要转折",sourceQuote:"最终正文连续短句",elapsedPartsFromStart:[{amount:从起点0累计到此时的非负数量,unit:"YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND"}]}],to:"实际最后停点",dayNumbering:"正文统一起点与日序",basis:"简短起讫说明"}。最后一项必须是最终正文的真实末尾，程序只取最后累计位置换算，不将转折相加，不拿孤立日序代替真实间隔。时间依据只能来自你本次最终正文，不补另一套新经历。
  仅返回上述两个根字段的JSON，不返回检查报告、归档表或未来建议。接下来的归档会从这唯一最终稿保存事实，不能再改正文或另估时间。
  """;
 ObjectNode read(ObjectNode facts,ObjectNode draft,ObjectNode projection,java.util.function.Consumer<String> received){
  if(!facts.path("allowPublicationEdits").asBoolean(false))return projection;
  boolean finishing=facts.path("finalizeManuscript").asBoolean(false);
  boolean reviewing=finishing && facts.path("manuscriptReview").asBoolean(false);
  var manuscript=finishing?draft:StoryPublicationRevision.apply(draft,projection,facts);
  if((!finishing && !"MANUSCRIPT_V1".equals(manuscript.at("/publicationRevision/format").asText())) || !reviewing && StoryPassageTime.seconds(manuscript).orElse(0)<86400)return projection;
  var input=JSON.createObjectNode().put("clockFormat",reviewing?"FOCUSED_MANUSCRIPT_V3_LONG_SPANS":finishing?"FINAL_MANUSCRIPT_V3_LONG_SPANS":"OFFSET_MILESTONES_V3_LONG_SPANS");input.put("story",manuscript.path("story").asText());
  if(finishing){var boundary=input.putObject("decisionBoundary");boundary.set("stop",draft.path("stop").deepCopy());if(draft.has("decision"))boundary.set("decision",draft.path("decision").deepCopy());}
  input.set("actualStart",StoryAuthorContext.latestPublishedPassage(facts));
  var endpoint=StoryContinuationContext.publishedEndpoint(facts);if(!endpoint.isEmpty())input.set("publishedEndpoint",endpoint);
  if(reviewing){StoryManuscriptReview.enrich(input,facts,draft);input.put("authoredTimeSourceHash",StoryArchiveService.source(draft.path("timeProgress")));}
  String source=StoryArchiveService.source(input);var out=projection.deepCopy();
  var saved=out.path("timeReading");
  if("DONE".equals(saved.path("status").asText()) && source.equals(saved.path("sourceHash").asText()))return out;
  String raw=null;
  if(source.equals(saved.path("sourceHash").asText()) && saved.path("rawResponse").isTextual()){
   try{publicationTiming(saved.path("rawResponse").asText(),manuscript,reviewing);if(reviewing)reviewReply(saved.path("rawResponse").asText(),manuscript.path("story").asText());else if(finishing)publicationReply(saved.path("rawResponse").asText());raw=saved.path("rawResponse").asText();}catch(IllegalArgumentException invalid){}
  }
  if(raw==null){
   var policy=reviewing?LLMRequestPolicy.STORY_REVIEW:finishing?LLMRequestPolicy.STORY_PUBLICATION:LLMRequestPolicy.STORY_TIME;
   var messages=new ArrayList<Map<String,String>>();messages.add(Map.of("role","system","content",(reviewing?StoryManuscriptReview.PROMPT:finishing?MANUSCRIPT_PROMPT:PROMPT)+StoryDuration.CALENDAR_PROMPT));messages.add(Map.of("role","user","content",input.toString()));
   if(reviewing)messages.add(Map.of("role","user","content","现在回到paragraphs逐段读，不要仅复述人物资料。先选真正有结论的原句检查：已经听过条件的人，后文是否又首次获知？同一目标已经获救/交付，后文是否又重新完成？证据中的观测值更高，在相同触发阈值下会更早还是更晚到线，原推论是否反了？不得把观测值偷偷改称阈值来替原稿辩护。即使上一章某人说过‘若规则被抬高便会迟’，也只证明提出了这个假设，不证明规则真被改；原始记录类型仍按原文，不按此人的解释改分类。若本稿把假设升级为确认，却只有观测记录差异，没有规则前后原文，就修清本稿所有相关断言、内心与后续复述为实际能确认的差异，原因仍待查。不能只改最初一句，后面又当已查明。再回到startingPoint末尾，接着其时段通读到本稿末尾；同场接着说话不另添半天。把确有问题的原段实际修改为paragraphRevisions，五类阅读不得互相代替；不用返回整篇。"));
   var response=policy.call(()->model.chatForJson(ModelProtocol.messages(reviewing?ModelProtocol.Stage.REVIEW:finishing?ModelProtocol.Stage.PUBLICATION:ModelProtocol.Stage.TIME,messages)));
   raw=Objects.toString(response.parsedJson()!=null?response.parsedJson():response.content(),"");
   out.putObject("timeReading").put("status","RECEIVED").put("sourceHash",source).put("rawResponse",raw);
   received.accept(out.toString());
  }
  var time=publicationTiming(raw,manuscript,reviewing);var reviewed=reviewing?reviewReply(raw,manuscript.path("story").asText()):null;
  var audit=out.putObject("timeReading").put("status","DONE").put("sourceHash",source).put("rawResponse",raw);
  audit.set("earlierPublicationTime",out.at("/publication/timeProgress").deepCopy());
  out.withObject("publication").set("timeProgress",time);
  if(finishing){var finalPublication=(reviewing?reviewed:publicationReply(raw)).path("publication");var finalText=finalPublication.path("story");if(finalText.isTextual() && !finalText.asText().isBlank()){out.withObject("publication").set("story",finalText.deepCopy());out.withObject("publication").remove("unchanged");}else if(finalPublication.path("unchanged").asBoolean(false)){out.withObject("publication").put("unchanged",true);out.withObject("publication").remove("story");}}
  if(reviewing){
   var review=out.putObject("manuscriptReview").put("version","SOURCE_AWARE_V2_TIMING_RECORD");
   review.put("timeSource",switch(time.path("recordingMethod").asText()){
    case "AUTHORED_TIME_SAME_CHRONOLOGY"->"AUTHOR";
    case "EXPLICIT_AUTHOR_RECORD_CORRECTION"->"CORRECTED_AUTHOR_RECORD";
    default->"REVISED_CHRONOLOGY";
   });
   if(reviewed.path("timingCorrection").isObject())review.set("timingCorrection",reviewed.path("timingCorrection").deepCopy());
   review.set("cast",reviewed.path("cast").deepCopy());
  }
  received.accept(out.toString());return out;
 }
 private JsonNode reviewReply(String raw,String original){
  var root=StoryManuscriptReview.reviewedReply(ModelProtocol.object(ModelProtocol.Stage.REVIEW,raw),original);var publication=root.path("publication");
  if((!publication.path("unchanged").asBoolean(false) && publication.path("story").asText().isBlank()) || !root.path("cast").isArray())
   throw new IllegalArgumentException("定稿回复缺少完整正文/unchanged或cast数组，原回复已保留，只续接定稿，不重抽角色和故事初稿");
  return root;
 }
 private ObjectNode publicationReply(String raw){
  var root=ModelProtocol.object(ModelProtocol.Stage.PUBLICATION,raw);
  // Frozen source-clock replies predate the publication section. Missing means
  // keep the exact manuscript; an explicitly malformed section still fails.
  if(!root.has("publication"))root.putObject("publication").put("unchanged",true);
  var publication=root.path("publication");
  if(!publication.path("unchanged").asBoolean(false) && publication.path("story").asText().isBlank())throw new IllegalArgumentException("定稿缺少publication.story或unchanged；原回复保留，只恢复定稿阶段");
  return root;
 }
 private ObjectNode publicationTiming(String raw,ObjectNode manuscript,boolean reviewing){
  if(reviewing){
   var reply=reviewReply(raw,manuscript.path("story").asText());var publication=reply.path("publication");
   boolean changedText=publication.path("story").isTextual() && !publication.path("story").asText().equals(manuscript.path("story").asText());
   String finalStory=changedText?publication.path("story").asText():manuscript.path("story").asText();
   var correction=reply.path("timingCorrection");
   // The existing main-author review may explicitly correct its own record.
   // An ordinary alternative estimate, or a malformed optional correction,
   // never silently replaces a valid clock or blocks publication.
   if(correction.path("required").asBoolean(false) && !correction.path("reason").asText().isBlank() && correction.path("sourceQuotes").isArray()){
    boolean quoted=false;for(var quote:correction.path("sourceQuotes"))if(quote.isTextual() && !quote.asText().isBlank() && finalStory.contains(quote.asText()))quoted=true;
    if(quoted)try{return timing(raw).put("recordingMethod","EXPLICIT_AUTHOR_RECORD_CORRECTION");}catch(IllegalArgumentException malformedCorrection){}
   }
   if((!changedText || !reply.path("chronologyChanged").asBoolean(false)) && StoryPassageTime.seconds(manuscript).isPresent()){
    var time=manuscript.path("timeProgress") instanceof ObjectNode object?object.deepCopy():JSON.createObjectNode();
    time.putArray("elapsedParts").addObject().put("amount",StoryPassageTime.seconds(manuscript).orElseThrow()).put("unit","SECOND");
    time.put("recordingMethod","AUTHORED_TIME_SAME_CHRONOLOGY");return time;
   }
  }
  return timing(raw);
 }
 private ObjectNode timing(String raw){
  var root=ModelProtocol.object(ModelProtocol.Stage.TIME,raw);var value=root.path("timeProgress");
  if(value instanceof ObjectNode time){
   var out=time.deepCopy();var milestones=time.path("milestones");
   var calendar=StoryDuration.calendarSeconds(time.path("calendarInterval"));
   if(calendar.isPresent()){
    out.putArray("elapsedParts").addObject().put("amount",calendar.getAsLong()).put("unit","SECOND");
    out.put("recordingMethod","CALENDAR_ENDPOINT_ARITHMETIC");return out;
   }
   if(milestones.isArray() && !milestones.isEmpty()){
    var end=milestones.get(milestones.size()-1).path("elapsedPartsFromStart");
    if(StoryDuration.seconds(end).isPresent()){out.set("elapsedParts",end.deepCopy());out.put("recordingMethod","FINAL_OFFSET_FROM_PUBLISHED_END");return out;}
   }
   // Old cached readers may have supplied one explicit duration directly.
   if(StoryDuration.seconds(time.path("elapsedParts")).isPresent())return out;
  }
  throw new IllegalArgumentException("正文时间记录缺少最终累计位置的原数量与单位；原回复已保存，只续接时间记录，不重抽故事和角色");
 }
}
