package com.bs.narrative.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionObservationTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void missingNpcObservationKeepsActualPositionWithoutLeakingFullProcessOrPrivateActivity(){
  var actual=json.createObjectNode().put("actorId","npc").put("kind","ACTION_RESULT").put("text","私有判断：秘密暗号").put("status","COMPLETED");
  var candidate=json.createObjectNode().put("process","私有判断：秘密暗号").put("visibility","PUBLIC");var body=candidate.putArray("effects").addObject().put("kind","BODY").put("actorId","npc");body.putObject("before").put("position","平石旁");body.putObject("after").put("position","碑脚水边一侧，离平石半步，侧身俯向浅刻线").put("activity","藏着不说的秘密");actual.set("effects",candidate.path("effects").deepCopy());
  var seen=ExecutionObservation.forObserver(actual,candidate,"player","沈砚舟");assertTrue(seen.path("text").asText().contains("碑脚水边"));assertFalse(seen.toString().contains("秘密"));assertFalse(seen.has("status"));assertFalse(seen.path("text").asText().contains("眼前可见的变化"));
 }
 @Test void explicitPublicObservationRemainsVerbatimAndFacilityFallbackUsesActualChange(){
  var actual=json.createObjectNode().put("actorId","npc");var candidate=json.createObjectNode();var facility=candidate.putArray("effects").addObject().put("kind","FACILITY").put("objectName","古碑").put("before","湿苔覆盖").put("after","碑脚湿苔拨开一小片，露出浅刻线");actual.set("effects",candidate.path("effects").deepCopy());
  assertTrue(ExecutionObservation.forObserver(actual,candidate,"player","沈砚舟").path("text").asText().contains(facility.path("after").asText()));
  candidate.putArray("observations").addObject().put("channel","VISUAL").put("text","沈砚舟俯身看碑脚的浅刻线。");assertEquals("沈砚舟俯身看碑脚的浅刻线。",ExecutionObservation.forObserver(actual,candidate,"player","沈砚舟").path("text").asText());
 }
}
