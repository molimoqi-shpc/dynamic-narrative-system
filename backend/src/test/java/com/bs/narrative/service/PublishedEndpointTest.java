package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bs.narrative.llm.*;
import java.util.*;

class PublishedEndpointTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode facts(){
  var f=json.createObjectNode().put("storyFirst",true).put("storyArchive",true).put("allowPublicationEdits",true).put("finalizeManuscript",true).put("manuscriptReview",true);
  f.putArray("previousChapters").addObject().put("logId",81762).put("story","阿德里安坐下，米拉说今晚要守着病人。这里的今晚是未来。");
  var state=f.putObject("continuityState").putObject("narrativeState");state.putObject("source").put("logId",81762);state.putObject("actualTime").put("to","同日上午，前厅炉火旁");return f;
 }
 @Test void morningEndpointReachesBothActualAuthorAndActualReviewerRequests()throws Exception{
  var f=facts();var d=json.createObjectNode().put("story","两人照看病人过夜，到次日清早归还信物。").put("stop","RESOLVED");
  d.putObject("timeProgress").put("to","CANDIDATE_NOT_A_PUBLISHED_SOURCE").putArray("elapsedParts").addObject().put("unit","DAY").put("amount",999);
  var interval=d.withObject("timeProgress").putObject("calendarInterval");interval.putObject("from").put("day",0).put("hour",10);interval.putObject("to").put("day",1).put("hour",8);
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(c->{
   List<Map<String,String>> messages=c.getArgument(0);var input=json.readTree(messages.get(1).get("content"));
   if(LLMRequestPolicy.current()==LLMRequestPolicy.STORY_REVIEW){
    assertEquals("同日上午，前厅炉火旁",input.at("/startingPoint/publishedEndpoint/to").asText());assertEquals("同日上午，前厅炉火旁",input.at("/publishedEndpoint/to").asText());
    assertFalse(input.path("publishedEndpoint").toString().contains("CANDIDATE_NOT_A_PUBLISHED_SOURCE"));assertEquals(d.path("timeProgress"),input.path("authoredTimeProgress"),"The candidate clock is separately labelled for record correction, never used as the published start");
    String raw="{\"paragraphRevisions\":[],\"cast\":[],\"timeProgress\":{\"to\":\"次日清早\",\"milestones\":[{\"elapsedPartsFromStart\":[{\"amount\":22,\"unit\":\"HOUR\"}]}]}}";return new LLMResponse(raw,raw,0,0,0,0);
   }
   var last=json.readTree(messages.get(messages.size()-1).get("content"));assertEquals("同日上午，前厅炉火旁",last.at("/continuation/publishedEndpoint/to").asText());return new LLMResponse("{}","{}",0,0,0,0);
  });
  var out=new StorySourceClock(model).read(f,d,json.createObjectNode(),raw->{});assertEquals(22*3600,StoryPassageTime.seconds(out.path("publication")).orElseThrow());
  new SceneStoryService(model).write(f,null,"",System.nanoTime()+60_000_000_000L);verify(model,times(2)).chatForJson(anyList());
 }
 @Test void committedNewestPassageSupersedesAnOlderChapterAndStaleSummary(){
  var f=facts();f.putArray("alreadyHappenedThisChapter").add("第一片上午").add("第二片午后");var results=f.putArray("committedPassageResults");
  results.addObject().put("passageIndex",0).putObject("timeProgress").put("to","第一片上午");results.addObject().put("passageIndex",1).putObject("timeProgress").put("to","第二片午后");
  var before=f.deepCopy();var source=StoryContinuationContext.publishedEndpoint(f);assertEquals("第二片午后",source.path("to").asText());assertEquals("/alreadyHappenedThisChapter/1",source.path("sourceRef").asText());assertEquals(before,f);
 }
 @Test void absentOrMismatchedSourcesDoNotInventAnEndpoint(){
  var f=facts();((ObjectNode)f.at("/continuityState/narrativeState/source")).put("logId",1);assertTrue(StoryContinuationContext.publishedEndpoint(f).isEmpty());
  f.putArray("alreadyHappenedThisChapter").add("更新片段但没有时间归档");assertTrue(StoryContinuationContext.publishedEndpoint(f).isEmpty());
 }
 @Test void databaseLongAndPersistedJsonIntegerIdentifyTheSamePublishedSource(){
  var f=facts();((ObjectNode)f.at("/previousChapters/0")).put("logId",81762L);
  assertEquals("同日上午，前厅炉火旁",StoryContinuationContext.publishedEndpoint(f).path("to").asText());
  assertTrue(StoryContinuationContext.handoff(f).path("memoryMatchesLatestChapter").asBoolean());
 }
}
