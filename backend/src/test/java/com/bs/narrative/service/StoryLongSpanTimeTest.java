package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryLongSpanTimeTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode facts(){return json.createObjectNode().put("allowPublicationEdits",true).put("finalizeManuscript",true).put("manuscriptReview",true);}
 ObjectNode draft(String text,long amount,String unit){
  var d=json.createObjectNode().put("story",text).put("stop","RESOLVED");
  d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",amount).put("unit",unit);return d;
 }
 ObjectNode review(long amount,String unit){
  var r=json.createObjectNode().put("chronologyChanged",false);r.putArray("paragraphRevisions");r.putArray("cast");
  r.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",amount).put("unit",unit);return r;
 }
 @ParameterizedTest @CsvSource({"30,YEAR,946080000","100,YEAR,3153600000","2,MONTH,5184000","3,WEEK,1814400"})
 void originalLongUnitsSurviveFinalizationAndResultOnlyArchiving(long amount,String unit,long expected)throws Exception{
  var d=draft("岁月过去，原文不分析为时间关键词。",amount,unit);var r=review(1,"HOUR");var model=mock(LLMProvider.class);
  when(model.chatForJson(anyList())).thenReturn(new LLMResponse(r.toString(),r.toString(),0,0,0,0));
  var clock=new StorySourceClock(model);var projection=clock.read(facts(),d,json.createObjectNode(),saved->{});
  var finalDraft=StoryPublicationRevision.apply(d,projection,facts());assertEquals(expected,finalDraft.path("elapsedSeconds").asLong());
  assertEquals(expected,new StoryArchiveService(null).normalize(StoryPassageTime.bind(finalDraft,json.createObjectNode()).toString()).path("elapsedSeconds").asLong());
  assertEquals(projection,clock.read(facts(),d,projection,saved->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @RepeatedTest(3) void explicitMainAuthorCorrectionCanFixARecordWithoutRewritingTheCapturedLife()throws Exception{
  var captured=json.readTree(getClass().getResourceAsStream("/fixtures/retirement-long-span-clock.json"));
  var d=json.createObjectNode().put("story",captured.path("story").asText()).put("stop","RESOLVED");d.set("timeProgress",captured.path("timeProgress").deepCopy());var before=d.deepCopy();
  // A simulated correction exercises the transport; it is not a new live-model verdict.
  var r=review(40,"YEAR");var correction=r.putObject("timingCorrection").put("required",true).put("reason","正文经过多年直到暮年，30 DAY仅记了一个月，漏记全篇跨度");
  correction.putArray("sourceQuotes").add(captured.path("sourceQuote").asText());
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(call->{
   List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   assertEquals(d.path("timeProgress"),input.path("authoredTimeProgress"));
   assertTrue(messages.get(0).get("content").contains("timingCorrection"));return new LLMResponse(r.toString(),r.toString(),0,0,0,0);
  });
  var clock=new StorySourceClock(model);var projection=clock.read(facts(),d,json.createObjectNode(),saved->{});
  var accepted=StoryPublicationRevision.apply(d,projection,facts());assertEquals(40L*31536000,accepted.path("elapsedSeconds").asLong());
  assertEquals(d.path("story"),accepted.path("story"));assertEquals(before,d);assertEquals(d.path("timeProgress"),accepted.at("/publicationRevision/originalTimeProgress"));
  assertEquals("CORRECTED_AUTHOR_RECORD",projection.at("/manuscriptReview/timeSource").asText());
  assertEquals(40L*31536000,StoryPassageTime.bind(accepted,json.createObjectNode()).path("elapsedSeconds").asLong());
  assertEquals(projection,clock.read(facts(),d,projection,saved->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void anIncompleteOrUnanchoredOptionalCorrectionDoesNotBlockPublicationOrReplaceTheAuthor()throws Exception{
  var d=draft("又过一日，银徽原样归还。",1,"DAY");
  for(int variant=0;variant<4;variant++){
   var r=review(12,"HOUR");var c=r.putObject("timingCorrection").put("required",true).put("reason","可定位的记录问题");c.putArray("sourceQuotes").add("又过一日");
   if(variant==0)c.remove("reason");if(variant==1)c.putArray("sourceQuotes").add("正文没有这句话");if(variant==2)r.remove("timeProgress");if(variant==3)c.put("required",false);
   var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenReturn(new LLMResponse(r.toString(),r.toString(),0,0,0,0));
   var out=new StorySourceClock(model).read(facts(),d,json.createObjectNode(),saved->{});
   assertEquals("AUTHOR",out.at("/manuscriptReview/timeSource").asText());assertEquals(86400,StoryPublicationRevision.apply(d,out,facts()).path("elapsedSeconds").asLong());
  }
 }
 @Test void authorAndArchiveContractsDescribeTheSameLongUnits(){
  assertTrue(StoryFirstContract.AUTHORING_PROMPT.contains("YEAR/MONTH/WEEK"));
  var focus=new StoryArchiveService(null).reviewFocus(json.createObjectNode(),json.createObjectNode());
  StoryDuration.SECONDS_PER_UNIT.forEach((unit,value)->assertEquals(value.longValue(),focus.at("/timing/secondsPerUnit/"+unit).asLong()));
 }
}
