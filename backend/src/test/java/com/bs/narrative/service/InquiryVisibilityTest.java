package com.bs.narrative.service;

import com.bs.narrative.domain.WorldBook;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class InquiryVisibilityTest {
    @Test void highAttributesDoNotUnlockSecretsOrUnknownVisibility() {
        var repo = mock(WorldBookRepository.class);
        var service = new WorldBookService(repo, mock(GameSceneRepository.class), mock(SessionStateService.class));
        WorldBook publicEvent = event(false), secret = event(true), unknown = event(null);
        when(repo.findBySessionIdAndCoordXAndCoordYOrderByRoundNoAsc("s", 0, 0))
                .thenReturn(List.of(publicEvent, secret, unknown));
        assertEquals(List.of(publicEvent), service.queryForInquiryWeighted("s", 0, 0, 10000, 10000));
        assertEquals(List.of(publicEvent), service.queryForInquiry("s", 0, 0));
    }
    private WorldBook event(Boolean secret) {
        var event = new WorldBook();
        event.setIsSecret(secret); event.setImportance(1); event.setRoundNo(1);
        return event;
    }
    @Test void unavailableDecisionContainsNoInventedSpeechThoughtOrChoice() {
        var decision = NpcAgentService.unavailable("a");
        assertTrue(decision.usedFallback());
        assertEquals("", decision.speech());
        assertEquals("", decision.innerThought());
        assertEquals("", decision.actionText());
        assertEquals("NONE", decision.decisionPoint());
        assertNull(decision.plannedStep());
    }
}
