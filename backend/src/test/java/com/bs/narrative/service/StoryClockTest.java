package com.bs.narrative.service;
import com.bs.narrative.llm.ModelProtocol;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryClockTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode at(long year,int day,String period){return json.createObjectNode().put("year",year).put("day",day).put("period",period);}
 ObjectNode frame(JsonNode end){var f=json.createObjectNode();f.set("endMoment",end);f.putArray("scenes").addObject().put("key","end").put("content","本篇实际结果").set("at",end);return f;}
 ObjectNode facts(JsonNode start){var f=json.createObjectNode();f.set("currentMoment",start);return f;}
 @Test void savedStartCannotBeReplacedByAnotherModelStart(){var f=frame(at(0,2,"MORNING"));f.set("startMoment",at(0,1,"NIGHT"));var b=StoryClockService.bind(f,facts(at(0,1,"MORNING")));assertEquals(86400,StoryPassageTime.seconds(b).orElseThrow());assertEquals("MORNING",b.at("/timeProgress/startMoment/period").asText());}
 @Test void fortyYearsRemainFortyYearsAndDoNotOverflowAnInteger(){var b=StoryClockService.bind(frame(at(40,1,"AFTERNOON")),facts(at(0,1,"AFTERNOON")));assertEquals(40L*365*86400,StoryPassageTime.seconds(b).orElseThrow());assertEquals("PERIOD",b.at("/timeProgress/precision").asText());}
 @Test void simultaneousSceneAndFlashbackNeverAddAnotherDuration(){var f=frame(at(10,8,"AFTERNOON"));var scenes=f.withArray("scenes");scenes.addObject().put("key","elsewhere").put("content","另一处同时发生").put("kind","PARALLEL").set("at",at(10,8,"AFTERNOON"));scenes.addObject().put("key","past").put("content","想起十年前的事").put("kind","FLASHBACK").set("at",at(0,1,"MORNING"));var b=StoryClockService.bind(f,facts(at(10,8,"MORNING")));assertEquals(21600,StoryPassageTime.seconds(b).orElseThrow());}
 @Test void samePeriodIsAllowedWithoutInventingHours(){var b=StoryClockService.bind(frame(at(0,1,"MORNING")),facts(at(0,1,"MORNING")));assertEquals(0,StoryPassageTime.seconds(b).orElseThrow());assertEquals("PERIOD",b.at("/timeProgress/precision").asText());}
 @Test void invalidCoordinatesCannotSilentlyBecomeZeroTime(){assertThrows(IllegalArgumentException.class,()->StoryClockService.bind(frame(at(0,0,"MORNING")),facts(at(0,1,"MORNING"))));assertThrows(IllegalArgumentException.class,()->StoryClockService.bind(frame(at(0,1,"DAWN")),facts(at(0,1,"MORNING"))));}
 @Test void explicitMinutesDetermineTheirOwnPeriodLabel(){var p=at(0,1,"MORNING").put("minuteOfDay",1260);var normalized=StoryClockService.moment(p);assertEquals("NIGHT",normalized.path("period").asText());assertEquals("MINUTE",normalized.path("precision").asText());}
 @Test void legacyStartNeedsAnExplicitCoordinateRatherThanAnInventedMidnight(){var f=frame(at(0,1,"AFTERNOON"));assertThrows(IllegalArgumentException.class,()->StoryClockService.bind(f,json.createObjectNode()));f.set("startMoment",at(0,1,"MORNING"));assertEquals(21600,StoryPassageTime.seconds(StoryClockService.bind(f,json.createObjectNode())).orElseThrow());}
 @Test void eachSceneCarriesOneCalculatedWindowAcrossDaysAndYears(){
  var f=frame(at(1,2,"EVENING"));f.withArray("scenes").insert(0,json.createObjectNode().put("key","travel").put("content","旅途").set("at",at(0,365,"AFTERNOON")));
  var b=StoryClockService.bind(f,facts(at(0,364,"MORNING")));
  assertEquals(1,b.at("/scenes/0/calendarWindow/daysSincePassageStart").asInt());assertEquals(2,b.at("/scenes/1/calendarWindow/daysSincePreviousScene").asInt());
  assertEquals(at(1,2,"EVENING").path("day"),b.at("/manuscriptEndpoint/moment/day"));assertEquals(365,b.at("/scenes/1/calendarWindow/from/day").asInt());
 }
 @Test void cachedRetirementFrameWithSingularSceneArrayKeepsTheExactAuthoredScenes() throws Exception {
  String raw;
  try(var input=getClass().getResourceAsStream("/fixtures/frame-singular-scene-20261029.json")){
   assertNotNull(input);raw=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  }
  var original=json.readTree(raw);var normalized=ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,raw);
  assertEquals(2,normalized.path("scenes").size());assertEquals(original.path("scene"),normalized.path("scenes"));assertFalse(normalized.has("scene"));
  var bound=StoryClockService.bind(normalized,facts(at(0,5,"AFTERNOON")));
  assertEquals(21600,StoryPassageTime.seconds(bound).orElseThrow());assertEquals("NIGHT",bound.at("/manuscriptEndpoint/moment/period").asText());
  assertEquals(original.path("lifeEnding"),bound.path("lifeEnding"));assertEquals("RETIREMENT",bound.at("/lifeEnding/kind").asText());
  assertTrue(bound.at("/lifeEnding/playerRequested").asBoolean());assertTrue(bound.at("/lifeEnding/fulfilled").asBoolean());
 }
}
