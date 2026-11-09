package com.bs.narrative.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoryDurationTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void shichenAndModernHoursHaveDifferentConversions()throws Exception{
  assertEquals(10800,StoryDuration.seconds(json.readTree("[{\"amount\":1.5,\"unit\":\"SHICHEN\"}]")).orElseThrow());
  assertEquals(5400,StoryDuration.seconds(json.readTree("[{\"amount\":1.5,\"unit\":\"HOUR\"}]")).orElseThrow());
 }
 @Test void mixedOriginalUnitsAndTheDocumentedNarrativeCalendarAreSupported()throws Exception{
  assertEquals(100920,StoryDuration.seconds(json.readTree("[{\"amount\":1,\"unit\":\"DAY\"},{\"amount\":2,\"unit\":\"SHICHEN\"},{\"amount\":2,\"unit\":\"MINUTE\"}]")).orElseThrow());
  assertEquals(2592000,StoryDuration.seconds(json.readTree("[{\"amount\":1,\"unit\":\"MONTH\"}]")).orElseThrow());
  assertEquals(604800,StoryDuration.seconds(json.readTree("[{\"amount\":1,\"unit\":\"WEEK\"}]")).orElseThrow());
  assertEquals(946080000,StoryDuration.seconds(json.readTree("[{\"amount\":30,\"unit\":\"YEAR\"}]")).orElseThrow());
 }
 @Test void missingMalformedOrOverflowingOptionalPartsAllowLegacyTimingFallback()throws Exception{
  for(String raw:new String[]{"null","[]","[{\"amount\":-1,\"unit\":\"DAY\"}]","[{\"amount\":\"一天\",\"unit\":\"DAY\"}]","[{\"amount\":9999999999999999999999,\"unit\":\"DAY\"}]"})assertTrue(StoryDuration.seconds(json.readTree(raw)).isEmpty());
 }
 @Test void archiveUsesOriginalUnitsAheadOfAnIncorrectLegacySecondsEstimate()throws Exception{
  var service=new StoryArchiveService(null);var result=service.normalize("{\"timeProgress\":{\"elapsedParts\":[{\"amount\":1.5,\"unit\":\"SHICHEN\"}],\"elapsedDays\":0,\"elapsedRemainderSeconds\":5400,\"elapsedSeconds\":5400}}");
  assertEquals(10800,result.at("/timeProgress/elapsedSeconds").asLong());assertEquals(5400,result.at("/timeProgress/reportedElapsedSeconds").asLong());assertEquals(result,service.normalize(result.toString()));
 }
}
