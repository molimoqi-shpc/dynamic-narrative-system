package com.bs.narrative.engine;
import com.bs.narrative.engine.enums.*;
import com.bs.narrative.engine.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NonCombatRegressionTest {
    @Test void unknownAndObserveNeverTriggerThirdPartyDamage() {
        for(var intent:List.of(IntentEnum.UNKNOWN,IntentEnum.OBSERVE)) {
            var p1=Pass1Intent.ofSimple(intent,"player","target");
            var result=ConflictResolver.resolve(p1,"player","target",Map.of(),Map.of(),
                    List.of(new InterventionScore("third","target",100,100,100,300,"高干预分")),List.of());
            assertEquals(OutcomeType.NO_OP,result.outcome());
            assertTrue(result.statDeltaPerGod().isEmpty());
            assertTrue(result.relationshipDeltaPerPair().isEmpty());
            assertFalse(result.noteZh().contains("雨师"), "裁决不得向无人场景加入人物");
            if (intent == IntentEnum.OBSERVE) {
                assertFalse(result.noteZh().contains("未被识别"), "观察是有效的非对抗行为");
            }
        }
    }
}
