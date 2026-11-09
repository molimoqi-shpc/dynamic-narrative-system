package com.bs.narrative.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DialogueOutcomeTest {
    @Test void speechToAnotherCharacterIsNotAPlayerReply() {
        var d = new NpcAgentService.NpcDecision("a", "RESPOND", "你先去门口", "", false, false, "b", "", "");
        assertEquals("OTHER_ADDRESSEE", DialogueOutcome.status(d,"player"));
    }
    @Test void refusalIsAnActualReplyAndSilenceDiffersFromTechnicalFailure() {
        assertEquals("SPOKEN", DialogueOutcome.status(NpcAgentService.of("a","RESPOND","不借","",false),"player"));
        assertEquals("NO_REPLY", DialogueOutcome.status(NpcAgentService.of("a","ACT","","",false),"player"));
        assertEquals("UNAVAILABLE", DialogueOutcome.status(NpcAgentService.unavailable("a"),"player"));
    }
}
