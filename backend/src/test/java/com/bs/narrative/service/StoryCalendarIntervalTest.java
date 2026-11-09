package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoryCalendarIntervalTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode interval(int day,int hour,int endDay,int endHour,int minute){
  var value=json.createObjectNode();value.putObject("from").put("day",day).put("hour",hour).put("minute",0);value.putObject("to").put("day",endDay).put("hour",endHour).put("minute",minute);return value;
 }
 @Test void clockEndpointsHandleTheRealMissingDaylightAndOtherSpansWithoutTextRules(){
  assertEquals(86400,StoryDuration.calendarSeconds(interval(0,10,1,10,0)).orElseThrow());
  assertEquals(75600,StoryDuration.calendarSeconds(interval(0,10,1,7,0)).orElseThrow());
  assertEquals(64800,StoryDuration.calendarSeconds(interval(0,14,1,8,0)).orElseThrow());
  assertEquals(3600,StoryDuration.calendarSeconds(interval(0,23,1,0,0)).orElseThrow());
  assertEquals(10*86400L+1800,StoryDuration.calendarSeconds(interval(0,10,10,10,30)).orElseThrow());
 }
 @RepeatedTest(3) void reviewedEndpointArithmeticWinsOverTheCapturedWrongTwelveHourEstimateAndIsCached()throws Exception{
  var facts=json.createObjectNode().put("allowPublicationEdits",true).put("finalizeManuscript",true).put("manuscriptReview",true);
  facts.putArray("previousChapters").addObject().put("logId",81762).put("story","同日上午，落水者安置后，米拉打算守他过夜。").putObject("timeProgress").put("to","同日上午，前厅炉火旁");
  var draft=json.createObjectNode().put("story","当天无事发生，众人照看病人过夜，到次日上午取回银徽。").put("stop","RESOLVED");
  var reply=json.createObjectNode();reply.putArray("paragraphRevisions");reply.putArray("cast");var time=reply.putObject("timeProgress").put("to","次日上午，前厅");time.set("calendarInterval",interval(0,10,1,10,0));
  time.putArray("milestones").addObject().putArray("elapsedPartsFromStart").addObject().put("unit","HOUR").put("amount",12);
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(c->{
   List<Map<String,String>> m=c.getArgument(0);assertTrue(m.get(0).get("content").contains("calendarInterval"));assertTrue(m.get(1).get("content").contains("同日上午，前厅炉火旁"));return new LLMResponse(reply.toString(),reply.toString(),0,0,0,0);
  });
  var clock=new StorySourceClock(model);var out=clock.read(facts,draft,json.createObjectNode(),raw->{});
  assertEquals("CALENDAR_ENDPOINT_ARITHMETIC",out.at("/publication/timeProgress/recordingMethod").asText());
  var finalDraft=StoryPublicationRevision.apply(draft,out,facts);assertEquals(86400,finalDraft.path("elapsedSeconds").asLong());assertEquals(draft.path("story"),finalDraft.path("story"));
  var bound=StoryPassageTime.bind(finalDraft,json.createObjectNode());assertEquals(86400,bound.path("elapsedSeconds").asLong());assertEquals(86400,StoryDuration.seconds(bound.at("/timeProgress/elapsedParts")).orElseThrow());
  assertEquals(out,clock.read(facts,draft,out,raw->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void acceptedEndClockPassesToTheNextChapterWhileItsRelativeDayStartsAgainAtZero(){
  var facts=json.createObjectNode();var c=facts.putArray("previousChapters").addObject().put("story","又过五日，午后归来。");
  c.putObject("timeProgress").put("to","第五日午后").set("calendarInterval",interval(0,8,5,15,30));
  var end=StoryContinuationContext.publishedEndpoint(facts);assertEquals(15,end.at("/clockAtEnd/hour").asInt());assertEquals(30,end.at("/clockAtEnd/minute").asInt());assertFalse(end.path("clockAtEnd").has("day"));
 }
 @Test void legacyDurationsRemainReadableAndMalformedOptionalCoordinatesDoNotChangeTheStory(){
  var draft=json.createObjectNode().put("story","旧存档原文");var time=draft.putObject("timeProgress");time.putArray("elapsedParts").addObject().put("unit","HOUR").put("amount",2);time.set("calendarInterval",interval(0,25,1,10,0));
  assertEquals(7200,StoryPassageTime.seconds(draft).orElseThrow());assertEquals("旧存档原文",draft.path("story").asText());
  assertTrue(StoryDuration.calendarSeconds(interval(0,10,0,9,0)).isEmpty());
 }
 @RepeatedTest(3) void theCapturedUnchangedStoryCannotBeRetimedFromOneDayToFifteenHours()throws Exception{
  var fixture=json.readTree(getClass().getResourceAsStream("/fixtures/unchanged-manuscript-clock.json"));var draft=(ObjectNode)fixture.path("draft");var facts=(ObjectNode)fixture.path("facts");String raw=fixture.path("review").asText();
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenReturn(new LLMResponse(raw,raw,0,0,0,0));
  var clock=new StorySourceClock(model);var out=clock.read(facts,draft,json.createObjectNode(),saved->{});var accepted=StoryPublicationRevision.apply(draft,out,facts);
  assertEquals(86400,accepted.path("elapsedSeconds").asLong());assertEquals(draft.path("story"),accepted.path("story"));assertEquals(out,clock.read(facts,draft,out,saved->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void anExplicitChangeToTheWrittenChronologyUsesItsRevisedCalendarEndpoints()throws Exception{
  var facts=json.createObjectNode().put("allowPublicationEdits",true).put("finalizeManuscript",true).put("manuscriptReview",true);
  var draft=json.createObjectNode().put("story","隔了一日，他们回来。");draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",1).put("unit","DAY");
  var reply=json.createObjectNode().put("chronologyChanged",true);reply.putArray("paragraphRevisions").addObject().put("paragraphId","P1").put("text","隔了两日，他们回来。");reply.putArray("cast");reply.putObject("timeProgress").set("calendarInterval",interval(0,8,2,8,0));
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenReturn(new LLMResponse(reply.toString(),reply.toString(),0,0,0,0));
  var out=new StorySourceClock(model).read(facts,draft,json.createObjectNode(),raw->{});var accepted=StoryPublicationRevision.apply(draft,out,facts);assertEquals(172800,accepted.path("elapsedSeconds").asLong());assertEquals("隔了两日，他们回来。",accepted.path("story").asText());
 }
}
