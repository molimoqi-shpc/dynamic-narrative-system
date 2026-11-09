package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Literal, source-bound editorial changes by the main API before first publication. */
final class StoryPublicationRevision {
 private StoryPublicationRevision() {}
 private record Edit(int from,int to,String text) {}
 static ObjectNode apply(ObjectNode draft,JsonNode projection,JsonNode facts){
  if(!facts.path("allowPublicationEdits").asBoolean(false))return draft.deepCopy();
  var publication=projection.path("publication");
  boolean completeText=publication.path("story").isTextual() && !publication.path("story").asText().isBlank();
  boolean finalSnapshot=completeText || publication.path("unchanged").asBoolean(false);
  if(finalSnapshot){
   var result=draft.deepCopy();if(completeText)result.set("story",publication.path("story").deepCopy());
   var audit=result.putObject("publicationRevision").put("format","MANUSCRIPT_V1").put("sourceHash",StoryArchiveService.source(draft)).put("originalStory",draft.path("story").asText());
   if(draft.has("timeProgress"))audit.set("originalTimeProgress",draft.path("timeProgress").deepCopy());
   {
    boolean unchangedText=!completeText || publication.path("story").equals(draft.path("story"));
    var authored=StoryPassageTime.seconds(draft);
    // The archive may copyedit a manuscript, but an unchanged manuscript has
    // already been timed by its author. Do not estimate the same interval twice.
    boolean dedicatedReading=facts.path("finalizeManuscript").asBoolean(false) || "DONE".equals(projection.at("/timeReading/status").asText());
    boolean keepAuthored=!dedicatedReading || "AUTHOR".equals(projection.at("/manuscriptReview/timeSource").asText());
    if(unchangedText && authored.isPresent() && keepAuthored){
     var time=draft.path("timeProgress") instanceof ObjectNode object?object.deepCopy():draft.objectNode();
     if(StoryDuration.calendarSeconds(time.path("calendarInterval")).isPresent() || StoryDuration.seconds(time.path("elapsedParts")).isEmpty())time.putArray("elapsedParts").addObject().put("amount",authored.getAsLong()).put("unit","SECOND");
     time.put("scope","THIS_PASSAGE").put("version","FINAL_PUBLICATION").put("recordingMethod","AUTHORED_UNCHANGED_MANUSCRIPT");
     result.set("timeProgress",time);result.put("elapsedSeconds",authored.getAsLong());
     if(publication.has("timeProgress"))audit.set("unusedArchiveTime",publication.path("timeProgress").deepCopy());
     return result;
    }
    var seconds=StoryPassageTime.seconds(publication);
    if(!publication.path("timeProgress").isObject() || seconds.isEmpty())throw new IllegalArgumentException("主API定稿的timeProgress须包含可读取的elapsedParts原数量与单位；保留已保存原稿，只补全同一份定稿，不重写已发表前文");
    var time=(ObjectNode)publication.path("timeProgress").deepCopy();time.put("scope","THIS_PASSAGE").put("version","FINAL_PUBLICATION");
    if(StoryDuration.calendarSeconds(time.path("calendarInterval")).isPresent())time.putArray("elapsedParts").addObject().put("amount",seconds.getAsLong()).put("unit","SECOND");
    result.set("timeProgress",time);result.put("elapsedSeconds",seconds.getAsLong());
   }
   return result;
  }
  String original=draft.path("story").asText();var accepted=new TreeMap<Integer,Edit>();
  var audit=draft.objectNode();var applied=audit.putArray("applied");var unused=audit.putArray("unapplied");
  for(var change:projection.at("/publication/edits")){
   String before=change.path("before").asText();int from=before.isEmpty()?-1:original.indexOf(before),to=from+before.length();
   boolean unique=from>=0 && original.indexOf(before,from+1)<0;
   boolean overlap=accepted.values().stream().anyMatch(edit->from<edit.to && to>edit.from);
   if(!change.path("before").isTextual() || !change.path("after").isTextual() || !unique || overlap){unused.add(change.deepCopy());continue;}
   accepted.put(from,new Edit(from,to,change.path("after").asText()));applied.add(change.deepCopy());
  }
  var result=draft.deepCopy();
  // A partly applied manuscript cannot share result tables written for a different text.
  // New replies return a complete manuscript; this branch only resumes old cached patches.
  if(!unused.isEmpty())throw new IllegalArgumentException("旧稿修订的原文锚点缺失、重复或重叠；请返回publication.story完整修订稿及其timeProgress，不再使用局部替换，原稿已保留");
  if(!accepted.isEmpty()){
   var text=new StringBuilder(original);for(var edit:accepted.descendingMap().values())text.replace(edit.from,edit.to,edit.text);
   if(!text.toString().isBlank())result.put("story",text.toString());
   else {unused.addAll(applied);applied.removeAll();}
  }
  if(!applied.isEmpty() || !unused.isEmpty()){
   audit.put("sourceHash",StoryArchiveService.source(draft));audit.put("originalStory",original);
   result.set("publicationRevision",audit);
  }
  return result;
 }
}
