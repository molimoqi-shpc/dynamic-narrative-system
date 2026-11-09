package com.bs.narrative.service;
import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorySourceClockTest {
 final ObjectMapper json=new ObjectMapper();final LLMProvider model=mock(LLMProvider.class);
 ObjectNode draft(){var d=json.createObjectNode().put("story","第五日商定，次日出行，再过一日办完家事，又过一日落雨，次日雨歇收船。");d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",999).put("unit","DAY");return d;}
 ObjectNode facts(){var f=json.createObjectNode().put("allowPublicationEdits",true);f.putArray("previousChapters").addObject().put("logId",50).put("story","此前已修好屋顶，点灯歇下。");return f;}
 ObjectNode projection(){var p=json.createObjectNode();p.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");return p;}
 String time(){var r=json.createObjectNode();var t=r.putObject("timeProgress").put("to","雨歇收船");var anchors=t.putArray("milestones");for(int day:List.of(5,6,6,7,8,9))anchors.addObject().putArray("elapsedPartsFromStart").addObject().put("amount",day).put("unit","DAY");return r.toString();}
 @Test void isolatedReadingDoesNotReceiveProvisionalNumbersAndItsCachedResultDoesNotPayAgain()throws Exception{
  when(model.chatForJson(anyList())).thenAnswer(c->{List<Map<String,String>> messages=c.getArgument(0);var in=json.readTree(messages.get(1).get("content"));assertEquals(draft().path("story"),in.path("story"));assertEquals(3,in.size());assertFalse(in.toString().contains("999"));assertEquals(LLMRequestPolicy.STORY_TIME,LLMRequestPolicy.current());return new LLMResponse(time(),time(),1,1,2,0);});
  var clock=new StorySourceClock(model);var before=projection();var out=clock.read(facts(),draft(),before,r->{});
  assertEquals("DONE",out.at("/timeReading/status").asText());assertEquals(7,out.at("/timeReading/earlierPublicationTime/elapsedParts/0/amount").asInt());
  var merged=new StoryArchiveService(model).merge(draft(),new StoryArchiveService(model).normalize(out.toString()),facts());assertEquals(777600,merged.path("elapsedSeconds").asLong());assertEquals(draft().path("story"),merged.path("story"));assertEquals(before,projection());
  assertEquals(out,clock.read(facts(),draft(),out,r->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void actualStartRetainsTheTimeAnchorEvenWhenItPrecedesTheLastFiveParagraphs()throws Exception{
  var f=facts();String previous="当日正午，众人出发。\n\n随后走过芦荡。\n\n核看闸口。\n\n听完村民说明。\n\n何九到岸边。\n\n阮七等待决定。";((ObjectNode)f.at("/previousChapters/0")).put("story",previous);
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);assertEquals(previous,json.readTree(messages.get(1).get("content")).path("actualStart").asText());return new LLMResponse(time(),time(),0,0,0,0);});
  new StorySourceClock(model).read(f,draft(),projection(),raw->{});verify(model,times(1)).chatForJson(anyList());
 }
 @Test void receivedTimeAnswerCanResumeFromDurableRawWithoutAnotherCall()throws Exception{
  when(model.chatForJson(anyList())).thenReturn(new LLMResponse(time(),time(),1,1,2,0));var saved=new ArrayList<ObjectNode>();var clock=new StorySourceClock(model);
  assertThrows(IllegalStateException.class,()->clock.read(facts(),draft(),projection(),raw->{try{saved.add((ObjectNode)json.readTree(raw));}catch(Exception e){throw new RuntimeException(e);}throw new IllegalStateException("storage response interrupted");}));
  assertEquals("RECEIVED",saved.get(0).at("/timeReading/status").asText());var recovered=clock.read(facts(),draft(),saved.get(0),r->{});assertEquals("DONE",recovered.at("/timeReading/status").asText());verify(model,times(1)).chatForJson(anyList());
 }
 @Test void malformedTimeAnswerIsSavedAndOnlyTheSmallReadingIsRetried()throws Exception{
  when(model.chatForJson(anyList())).thenReturn(new LLMResponse("broken time JSON",null,1,1,2,0),new LLMResponse(time(),time(),1,1,2,0));var saved=new ArrayList<ObjectNode>();var clock=new StorySourceClock(model);
  assertThrows(IllegalArgumentException.class,()->clock.read(facts(),draft(),projection(),raw->{try{saved.add((ObjectNode)json.readTree(raw));}catch(Exception e){throw new RuntimeException(e);}}));
  assertEquals("broken time JSON",saved.get(0).at("/timeReading/rawResponse").asText());assertEquals("DONE",clock.read(facts(),draft(),saved.get(0),r->{}).at("/timeReading/status").asText());verify(model,times(2)).chatForJson(anyList());
 }
 @Test void shortOrAlreadyPublishedManuscriptsDoNotAddAReadingCall(){
  var clock=new StorySourceClock(model);var f=facts();f.put("allowPublicationEdits",false);assertEquals(projection(),clock.read(f,draft(),projection(),r->{}));
  var p=projection();((ObjectNode)p.at("/publication/timeProgress")).putArray("elapsedParts").addObject().put("amount",5).put("unit","MINUTE");var shortDraft=draft();((ObjectNode)shortDraft.path("timeProgress")).set("elapsedParts",p.at("/publication/timeProgress/elapsedParts").deepCopy());assertEquals(p,clock.read(facts(),shortDraft,p,r->{}));verifyNoInteractions(model);
 }
 @Test void manuscriptModeFinalizesTheTextAndTimeTogetherAndCachesAgainstTheOriginalInput()throws Exception{
  var r=(ObjectNode)json.readTree(time());r.putObject("publication").put("story","第五日商定，第六日出行，第七日办完家事，第八日落雨，第九日雨歇收船。");
  when(model.chatForJson(anyList())).thenAnswer(c->{assertEquals(LLMRequestPolicy.STORY_PUBLICATION,LLMRequestPolicy.current());String raw=r.toString();return new LLMResponse(raw,raw,1,1,2,0);});
  var f=facts().put("finalizeManuscript",true);var clock=new StorySourceClock(model);var out=clock.read(f,draft(),projection(),s->{});
  var finalDraft=StoryPublicationRevision.apply(draft(),out,f);assertEquals(r.at("/publication/story"),finalDraft.path("story"));assertEquals(777600,finalDraft.path("elapsedSeconds").asLong());
  assertEquals(out,clock.read(f,draft(),out,s->{}));verify(model,times(1)).chatForJson(anyList());
 }
 @Test void malformedPublicationIsNotReusedForeverJustBecauseItsTimeWasReadable()throws Exception{
  var invalid=(ObjectNode)json.readTree(time());invalid.put("publication","错误的文字类型");var valid=(ObjectNode)json.readTree(time());valid.putObject("publication").put("unchanged",true);
  when(model.chatForJson(anyList())).thenReturn(new LLMResponse(invalid.toString(),invalid.toString(),0,0,0,0),new LLMResponse(valid.toString(),valid.toString(),0,0,0,0));
  var f=facts().put("finalizeManuscript",true);var clock=new StorySourceClock(model);var saved=new ArrayList<ObjectNode>();
  assertThrows(IllegalArgumentException.class,()->clock.read(f,draft(),projection(),raw->{try{saved.add((ObjectNode)json.readTree(raw));}catch(Exception e){throw new RuntimeException(e);}}));
  assertEquals("DONE",clock.read(f,draft(),saved.get(saved.size()-1),raw->{}).at("/timeReading/status").asText());verify(model,times(2)).chatForJson(anyList());
 }
}
