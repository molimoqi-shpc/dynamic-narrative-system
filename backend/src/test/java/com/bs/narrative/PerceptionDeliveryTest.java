package com.bs.narrative;
import com.bs.narrative.simulation.SpeechExposure;import com.bs.narrative.service.*;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PerceptionDeliveryTest{
 final ObjectMapper json=new ObjectMapper();
 Set<String> people(JsonNode n){var ids=new HashSet<String>();n.path("recipients").forEach(v->ids.add(v.asText()));return ids;}
 @Test void newcomerAtTheEndpointNeverHeardTheWholeUtterance(){var p=SpeechExposure.advance(json.createObjectNode(),0,4,0,4,List.of("speaker","old"),"speaker");assertEquals(Set.of("speaker","old"),people(p));}
 @Test void leavingAndReturningDoesNotRestoreMissingWords(){var p=SpeechExposure.advance(json.createObjectNode(),0,6,0,2,List.of("speaker","old"),"speaker");p=SpeechExposure.advance(p,0,6,2,4,List.of("speaker","new"),"speaker");p=SpeechExposure.advance(p,0,6,4,6,List.of("speaker","old","new"),"speaker");assertEquals(Set.of("speaker"),people(p));}
 @Test void endpointDepartureDoesNotRevokeThePreviousInterval(){var p=SpeechExposure.advance(json.createObjectNode(),0,4,0,4,List.of("speaker","old"),"speaker");p=SpeechExposure.advance(p,0,4,4,5,List.of("speaker"),"speaker");assertTrue(people(p).contains("old"));}
 @Test void intervalEvidenceSurvivesSerialization(){var p=SpeechExposure.advance(json.createObjectNode(),0,4,0,2,List.of("speaker","old"),"speaker");var saved=(ObjectNode)json.valueToTree(json.convertValue(p,Map.class));assertEquals(Set.of("speaker","old"),people(SpeechExposure.advance(saved,0,4,2,4,List.of("speaker","old","new"),"speaker")));}
 @Test void legacyOrMissingIntervalDoesNotInventAnAudience(){var p=SpeechExposure.advance(json.createObjectNode(),0,4,2,4,List.of("speaker","old"),"speaker");assertEquals(Set.of("speaker"),people(p));}
 @Test void aGapInEvidenceDoesNotGrantCompleteHearing(){var p=SpeechExposure.advance(json.createObjectNode(),0,6,0,2,List.of("speaker","old"),"speaker");p=SpeechExposure.advance(p,0,6,4,6,List.of("speaker","old"),"speaker");assertEquals(Set.of("speaker"),people(p));}
 @Test void privateProcessAndBodyExperienceDoNotEnterTheVisualProjection(){
  ObjectNode actual=json.valueToTree(Map.of("text","触感凉且刺痛","attempt","我悄悄找出暗号","effects",List.of(Map.of("kind","BODY","actorId","a","before",Map.of("percepts",List.of(),"activity","私下判断"),"after",Map.of("percepts",List.of("刺痛"),"activity","怀疑暗号","bound",false)))));
  var candidate=json.valueToTree(Map.of("process","触感凉且刺痛","observations",List.of(Map.of("channel","VISUAL","text","甲伸手摸了石面。","action","触摸石面"))));
  var visible=ExecutionObservation.forObserver(actual,candidate,"b","甲");assertFalse(visible.toString().contains("刺痛"));assertFalse(visible.toString().contains("暗号"));assertEquals("触摸石面",visible.path("attempt").asText());assertTrue(visible.toString().contains("伸手"));assertTrue(actual.toString().contains("刺痛"));
 }
 @Test void legacyProcessIsNotSilentlyReclassifiedAsPublic(){ObjectNode actual=json.valueToTree(Map.of("text","他感觉刺痛","attempt","秘密计划","effects",List.of()));var projected=ExecutionObservation.forObserver(actual,json.valueToTree(Map.of("process","他感觉刺痛")),"b","甲");assertFalse(projected.toString().contains("刺痛"));assertFalse(projected.has("attempt"));}
 @Test void privateChannelsCannotBeMisroutedThroughVisualSchema(){assertThrows(IllegalArgumentException.class,()->ExecutionObservation.validate(json.valueToTree(Map.of("observations",List.of(Map.of("channel","SELF","text","疼痛"))))));}
 @Test void privateOnlyResultsAreNotEvenAnnouncedAsPublicSuccess(){assertFalse(ExecutionObservation.hasVisualEvidence(json.valueToTree(Map.of("process","只有本人知道","effects",List.of(Map.of("kind","BODY","after",Map.of("percepts",List.of("刺痛"))))))));assertTrue(ExecutionObservation.hasVisualEvidence(json.valueToTree(Map.of("effects",List.of(Map.of("kind","BODY","after",Map.of("bound",false)))))));}
 @Test void extraEffectMetadataIsNotCopiedToObservers(){ObjectNode event=json.valueToTree(Map.of("text","原始私有经过","effects",List.of(Map.of("kind","BODY","actorId","a","privateNote","只有本人知道","before",Map.of("bound",true),"after",Map.of("bound",false)))));var visible=ExecutionObservation.forObserver(event,json.createObjectNode(),"b","甲");assertFalse(visible.toString().contains("只有本人知道"));assertFalse(visible.toString().contains("privateNote"));assertTrue(visible.toString().contains("bound"));}
 @Test void unchangedPrivateBodyMetadataIsNotAnObservableChange(){assertFalse(ExecutionObservation.hasVisualEvidence(json.valueToTree(Map.of("effects",List.of(Map.of("kind","BODY","before",Map.of("bound",false),"after",Map.of("bound",false)))))));}

 @Test void privateGoalOutcomeIsNotPublicStatusOrInventedProse(){
  for(String status:List.of("COMPLETED","BLOCKED","ONGOING")){
   ObjectNode actual=json.valueToTree(Map.of("text","私有判断","status",status,"effects",List.of(Map.of("kind","BODY","actorId","a","before",Map.of("position","门边"),"after",Map.of("position","墙边")))));
   var projected=ExecutionObservation.forObserver(actual,json.valueToTree(Map.of("status",status)),"b","甲");
   assertFalse(projected.has("status"));assertFalse(projected.path("text").asText().contains("完成"));assertFalse(projected.path("text").asText().contains("阻碍"));assertFalse(projected.path("text").asText().contains("继续"));assertTrue(projected.toString().contains("墙边"));assertEquals(status,actual.path("status").asText());
  }
 }
 @Test void explicitVisibleOutcomeRetainsItsDescriptionWithoutPrivateStatus(){
  ObjectNode actual=json.valueToTree(Map.of("status","BLOCKED","text","私有目标失败","effects",List.of()));
  var projected=ExecutionObservation.forObserver(actual,json.valueToTree(Map.of("observations",List.of(Map.of("channel","VISUAL","text","甲拉动门把，门仍然关闭。")))),"b","甲");assertEquals("甲拉动门把，门仍然关闭。",projected.path("text").asText());assertFalse(projected.has("status"));
 }

 @Test void visibleActionOutcomeCanDifferFromThePrivateGoal(){
  ObjectNode actual=json.valueToTree(Map.of("status","BLOCKED","text","私有目标未达成","effects",List.of()));
  var candidate=json.valueToTree(Map.of("observations",List.of(Map.of("channel","VISUAL","text","甲已走到墙边。","action","走到墙边","status","COMPLETED"))));ExecutionObservation.validate(candidate);
  var projected=ExecutionObservation.forObserver(actual,candidate,"b","甲");assertEquals("COMPLETED",projected.path("status").asText());assertEquals("BLOCKED",actual.path("status").asText());
 }
 @Test void visibleOutcomeMustBindToAnActionAndCannotMergeConflictingOutcomes(){
  var ordinary=json.valueToTree(Map.of("observations",List.of(Map.of("channel","VISUAL","text","甲停下。","action","","status","COMPLETED"))));ExecutionObservation.validate(ordinary);
  var seen=ExecutionObservation.forObserver(json.createObjectNode().put("status","COMPLETED"),ordinary,"b","甲");assertEquals("甲停下。",seen.path("text").asText());assertFalse(seen.has("status"));assertFalse(seen.has("attempt"));
  assertThrows(IllegalArgumentException.class,()->ExecutionObservation.validate(json.valueToTree(Map.of("observations",List.of(Map.of("channel","VISUAL","text","甲停下。","status","INVENTED"))))));
  var candidate=json.valueToTree(Map.of("observations",List.of(Map.of("channel","VISUAL","text","甲到墙边。","action","走到墙边","status","COMPLETED"),Map.of("channel","VISUAL","text","门未打开。","action","拉门","status","BLOCKED"))));
  assertFalse(ExecutionObservation.forObserver(json.createObjectNode().put("status","COMPLETED"),candidate,"b","甲").has("status"));
 }
}
