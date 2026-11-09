package com.bs.narrative.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class StoryPassageTimeTest {
 final ObjectMapper json=new ObjectMapper();
 @ParameterizedTest @CsvSource({"1.5,SHICHEN,5400,216000,10800","10,DAY,864000,216000,864000","7,DAY,691200,21600,604800","0,SECOND,80,999,0","0.5,MINUTE,90,2,30"})
 void authoredOriginalUnitsOwnTheClockAcrossWrongRawEstimatesAndArchiveInterpretations(double amount,String unit,long raw,long wrong,long expected){
  var draft=json.createObjectNode().put("story","原稿完整保留，不作关键词分析").put("elapsedSeconds",raw);
  draft.putObject("timeProgress").put("scope","THIS_PASSAGE").putArray("elapsedParts").addObject().put("amount",amount).put("unit",unit);
  var archive=json.createObjectNode();archive.putObject("timeProgress").put("elapsedSeconds",wrong);var originalDraft=draft.deepCopy();var originalArchive=archive.deepCopy();
  var projected=StoryPassageTime.bind(draft,archive);assertEquals(expected,projected.at("/timeProgress/elapsedSeconds").asLong());
  assertEquals(wrong,projected.at("/archiveTimeInterpretation/elapsedSeconds").asLong());assertEquals(originalDraft,draft);assertEquals(originalArchive,archive);
  assertEquals(projected,StoryPassageTime.bind(draft,projected),"A cached/recovered projection must not drift");
  assertEquals(expected,AuthoredStoryBinder.bind(draft,json.createObjectNode()).path("elapsedSeconds").asLong(),"Non-archive mode uses the same source");
 }
 @Test void legacyAuthorSecondsRemainAuthoritativeAndMissingAuthorTimeUsesArchiveCompatibility(){
  var draft=json.createObjectNode().put("elapsedSeconds",3600);var archive=json.createObjectNode();archive.putObject("timeProgress").put("elapsedSeconds",1296000);
  assertEquals(3600,StoryPassageTime.bind(draft,archive).at("/timeProgress/elapsedSeconds").asLong());draft.remove("elapsedSeconds");
  assertEquals(archive,StoryPassageTime.bind(draft,archive));
 }
 @Test void invalidOptionalUnitsFallBackWithoutBlockingTheStory(){
  var draft=json.createObjectNode().put("elapsedSeconds",60);draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",-1).put("unit","DAY");
  var out=StoryPassageTime.bind(draft,json.createObjectNode());assertEquals(60,out.at("/timeProgress/elapsedSeconds").asLong());assertEquals("SECOND",out.at("/timeProgress/elapsedParts/0/unit").asText());
 }
 @Test void cachedWrongArchiveCanBeReboundWithoutAnApiOrAnotherIncrement(){
  var draft=json.createObjectNode().put("elapsedSeconds",604800);var cached=json.createObjectNode();cached.putObject("timeProgress").put("elapsedSeconds",21600);
  long start=864000;var fixed=StoryPassageTime.bind(draft,cached);assertEquals(1468800,start+fixed.at("/timeProgress/elapsedSeconds").asLong());
  assertEquals(fixed,StoryPassageTime.bind(draft,fixed));assertEquals(604800,StoryPassageTime.seconds(draft).orElseThrow());
 }
}
