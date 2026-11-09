package com.bs.narrative;

import com.bs.narrative.service.OperationEffectService;
import com.bs.narrative.service.ConditionWaitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorldCandidateValidationTest {
    final OperationEffectService effects=new OperationEffectService(null,null,null,null,null,null);
    final ObjectMapper json=new ObjectMapper();
    ObjectNode packet(){var n=json.createObjectNode().put("process","继续实际工作。").put("durationSeconds",4).put("status","ONGOING").put("remainingSeconds",8);n.putArray("effects");return n;}
    @ParameterizedTest @ValueSource(strings={"durationSeconds","remainingSeconds"})
    void hugeIntegersCannotWrapIntoValidTiming(String field){var n=packet();n.put(field,BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(4)));assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n));}
    @Test void unfinishedWorkCannotMakeProgressInZeroSeconds(){var n=packet().put("durationSeconds",0);assertEquals("ongoing work needs elapsed time",assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @Test void zeroSecondTerminalObservationStillWorks(){var n=packet().put("status","COMPLETED").put("durationSeconds",0);assertDoesNotThrow(()->effects.validateCandidate(n));}
    @ParameterizedTest @ValueSource(strings={"UNSUPPORTED_TAIL","INVENTED_RESOURCE",""})
    void unknownMechanismsAreRejectedBeforeAnyCohortCommit(String kind){var n=packet();n.withArray("effects").addObject().put("kind",kind);assertEquals("effect mechanism unresolved: "+kind,assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @Test void bodyAfterCannotAddAnActivityWithoutItsBeforeValue(){var n=packet();var body=n.withArray("effects").addObject().put("kind","BODY").put("actorId","a");body.putObject("before").put("position","原地");body.putObject("after").put("position","原地").put("activity","拢铃");assertEquals("body effect needs matching before field",assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @Test void anUnsupportedBodyFieldCannotHideBehindARepairableMissingBefore(){var n=packet();var body=n.withArray("effects").addObject().put("kind","BODY").put("actorId","a");body.putObject("before");body.putObject("after").put("activity","施法").put("health",200);assertEquals("invalid body field",assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @ParameterizedTest @ValueSource(strings={"before","after"})
    void serverTimestampKeepsItsSpecificRepairReason(String part){var n=packet();var body=n.withArray("effects").addObject().put("kind","BODY").put("actorId","a");body.putObject("before").put("activity","旧活动");body.putObject("after").put("activity","新活动");((ObjectNode)body.path(part)).put("updatedAtTick",10);assertEquals("body timestamp is server owned",assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @ParameterizedTest @ValueSource(strings={"awake","position","percepts"})
    void bodyValueTypesAreValidatedBeforeAnyWrite(String field){var n=packet();var body=n.withArray("effects").addObject().put("kind","BODY").put("actorId","a");body.putObject("before").put(field,"旧值");body.putObject("after").put(field,17);assertEquals("invalid body field value",assertThrows(IllegalArgumentException.class,()->effects.validateCandidate(n)).getMessage());}
    @ParameterizedTest @ValueSource(strings={"如果我留在原地，仔细观察眼前人物。","我留在原地，不观察眼前人物。","我留在原地，准备将来观察眼前人物。","我只在心里想留在原地观察眼前人物。","我留在原地，观察远方密室。"})
    void legacyObservationCompatibilityCannotAuthorizeConditionsThoughtsNegationsOrRemoteSight(String text){assertFalse(ConditionWaitService.explicitStationaryObservation(text));}
}
