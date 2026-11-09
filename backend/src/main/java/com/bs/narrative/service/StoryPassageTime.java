package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.OptionalLong;

/** The story author owns passage duration. Archiving cannot create a second clock. */
final class StoryPassageTime {
 private StoryPassageTime(){}
 static OptionalLong seconds(JsonNode draft){
  var calendar=StoryDuration.calendarSeconds(draft.at("/timeProgress/calendarInterval"));if(calendar.isPresent())return calendar;
  var parts=StoryDuration.seconds(draft.at("/timeProgress/elapsedParts"));if(parts.isPresent())return parts;
  for(var value:java.util.List.of(draft.path("elapsedSeconds"),draft.at("/timeProgress/elapsedSeconds")))
   if(value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0)return OptionalLong.of(value.longValue());
  return OptionalLong.empty();
 }
 static ObjectNode bind(ObjectNode draft,ObjectNode archive){
  var authored=seconds(draft);if(authored.isEmpty())return archive.deepCopy();
  var out=archive.deepCopy();var old=archive.path("timeProgress");long value=authored.getAsLong();
  if(!out.has("archiveTimeInterpretation") && old.isObject() && old.path("elapsedSeconds").isNumber() && old.path("elapsedSeconds").asLong()!=value)out.set("archiveTimeInterpretation",old.deepCopy());
  var time=draft.path("timeProgress") instanceof ObjectNode object?object.deepCopy():out.objectNode();
  if(StoryDuration.calendarSeconds(time.path("calendarInterval")).isPresent() || StoryDuration.seconds(time.path("elapsedParts")).isEmpty()){
   time.putArray("elapsedParts").addObject().put("amount",value).put("unit","SECOND");
   // Legacy labels can be retained as interpretation, never as a replacement duration.
   for(String field:java.util.List.of("from","to","basis"))if(!time.has(field) && old.has(field))time.set(field,old.path(field).deepCopy());
  }
  time.put("elapsedSeconds",value).put("scope","THIS_PASSAGE").put("source","STORY_AUTHOR");
  out.set("timeProgress",time);out.put("elapsedSeconds",value);return out;
 }
}
