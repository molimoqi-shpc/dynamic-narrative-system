package com.bs.narrative.simulation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bs.narrative.simulation.SimulationTimeline.Kind.*;

class SimulationBoundaryTest {
    @Test void restoresReplacedBoundariesAndBatchesSimultaneousAttempts() {
        var timeline = new SimulationTimeline(0, List.of());
        timeline.schedule(new SimulationTimeline.Boundary("a", "a", 1, 10, DELIBERATION));
        timeline.schedule(new SimulationTimeline.Boundary("a", "a", 2, 20, DELIBERATION));
        timeline.schedule(new SimulationTimeline.Boundary("b", "b", 1, 20, PROCESS));
        assertTrue(timeline.next(15, OptionalLong.empty()).isEmpty());
        assertEquals(0, timeline.nowSeconds());
        var restored = new SimulationTimeline(timeline.nowSeconds(), timeline.snapshot());
        assertEquals(2, restored.next(30, OptionalLong.empty()).size());
        assertEquals(20, restored.nowSeconds());
        assertTrue(restored.next(30, OptionalLong.empty()).isEmpty());
    }
    @Test void unresolvedCausalityBlocksTimeAndDoesNotConsumePendingEvents() {
        var timeline = new SimulationTimeline(0, List.of(new SimulationTimeline.Boundary("a", "a", 1, 10, SPEECH)));
        assertTrue(timeline.next(30, OptionalLong.of(10)).isEmpty());
        assertEquals(0, timeline.nowSeconds());
        assertEquals(1, timeline.next(30, OptionalLong.empty()).size());
    }
    @Test void hiddenOrFutureInformationCannotCompleteWait() {
        var other = new PerceivedWait.Delivery("other", "e", 5, Set.of("leave"), Set.of());
        var future = new PerceivedWait.Delivery("player", "e2", 15, Set.of("leave"), Set.of());
        assertEquals(PerceivedWait.Status.PENDING, PerceivedWait.evaluate("player", "leave", 10, 30, List.of(other, future)));
        assertEquals(PerceivedWait.Status.SATISFIED, PerceivedWait.evaluate("player", "leave", 15, 30, List.of(future)));
        assertEquals(PerceivedWait.Status.BOUND_REACHED, PerceivedWait.evaluate("player", "leave", 30, 30, List.of()));
    }
}
