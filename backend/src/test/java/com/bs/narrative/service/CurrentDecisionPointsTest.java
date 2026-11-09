package com.bs.narrative.service;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class CurrentDecisionPointsTest {
 @Test void aFormerPhaseStaysInHistoryButCannotBeAnActiveQuestion(){
  var old=Map.<String,Object>of("phase",1,"text","旧问题");var now=Map.<String,Object>of("phase",3,"text","当前提议");
  assertEquals(List.of(now),CurrentDecisionPoints.atPhase(List.of(old,now),3));assertTrue(CurrentDecisionPoints.atPhase(List.of(old),3).isEmpty());
 }
 @Test void explicitEmptyActiveListDoesNotReviveHistoricalQuestions(){
  assertTrue(CurrentDecisionPoints.fromSnapshot(Map.of("activeDecisionPoints",List.of(),"decisionPoints",List.of(Map.of("phase",1,"text","旧问题")))).isEmpty());
 }
 @Test void legacyWaitUsesActualFinalPhaseAndLegacySingleRoundKeepsItsQuestion(){
  var point=Map.<String,Object>of("phase",1,"text","旧问题");
  assertTrue(CurrentDecisionPoints.fromSnapshot(Map.of("waiting",Map.of("phases",3),"decisionPoints",List.of(point))).isEmpty());
  assertEquals(List.of(point),CurrentDecisionPoints.fromSnapshot(Map.of("decisionPoints",List.of(point))));
 }
}
