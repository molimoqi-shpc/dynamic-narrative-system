package com.bs.narrative.simulation;

import java.util.Set;

/** Evaluate only server-accepted matches in the waiting role's delivered information. */
public final class PerceivedWait {
    public enum Status { PENDING, SATISFIED, KNOWN_INVALIDATED, BOUND_REACHED }
    public record Delivery(String recipientId, String eventId, long learnedAtSeconds,
                           Set<String> matchedConditions, Set<String> invalidatedConditions) {
        public Delivery {
            if (recipientId == null || eventId == null || learnedAtSeconds < 0)
                throw new IllegalArgumentException("Invalid delivery");
            matchedConditions = Set.copyOf(matchedConditions);
            invalidatedConditions = Set.copyOf(invalidatedConditions);
        }
    }
    private PerceivedWait() {}
    public static Status evaluate(String actorId, String conditionId, long nowSeconds,
                                  long expiresAtSeconds, Iterable<Delivery> deliveries) {
        if (actorId == null || conditionId == null || nowSeconds < 0 || expiresAtSeconds < 0)
            throw new IllegalArgumentException("Invalid wait");
        // Latest delivered interpretation wins; hidden world state is intentionally not an argument.
        long latest = -1;
        Status observed = Status.PENDING;
        for (Delivery delivery : deliveries) {
            if (!actorId.equals(delivery.recipientId()) || delivery.learnedAtSeconds() > nowSeconds
                    || delivery.learnedAtSeconds() < latest) continue;
            if (delivery.invalidatedConditions().contains(conditionId)) {
                observed = Status.KNOWN_INVALIDATED;
                latest = delivery.learnedAtSeconds();
            } else if (delivery.matchedConditions().contains(conditionId)) {
                observed = Status.SATISFIED;
                latest = delivery.learnedAtSeconds();
            }
        }
        if (observed != Status.PENDING) return observed;
        return nowSeconds >= expiresAtSeconds ? Status.BOUND_REACHED : Status.PENDING;
    }
}
