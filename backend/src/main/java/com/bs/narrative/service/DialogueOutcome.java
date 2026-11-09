package com.bs.narrative.service;

/** A character speaking is not necessarily answering the player. No truth or motive inference. */
public final class DialogueOutcome {
    private DialogueOutcome() {}
    public static String status(NpcAgentService.NpcDecision decision, String playerId) {
        if (decision == null || decision.usedFallback()) return "UNAVAILABLE";
        if (decision.speech() == null || decision.speech().isBlank()) return "NO_REPLY";
        String target = decision.targetGodId();
        return target == null || target.isBlank() || playerId.equals(target) ? "SPOKEN" : "OTHER_ADDRESSEE";
    }
}
