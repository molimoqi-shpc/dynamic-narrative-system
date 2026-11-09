package com.bs.narrative.simulation;

import java.util.*;

/** Story-time scheduler. Rebuild from persisted boundaries; never use wall-clock time. */
public final class SimulationTimeline {
    public enum Kind { PROCESS, SPEECH, DELIBERATION, DELIVERY, WAIT_LIMIT }
    public record Boundary(String id, String actorId, long epoch, long atSeconds, Kind kind) {
        public Boundary {
            if (id == null || id.isBlank() || actorId == null || epoch < 0 || atSeconds < 0 || kind == null)
                throw new IllegalArgumentException("Invalid simulation boundary");
        }
    }
    private final Map<String, Boundary> active = new HashMap<>();
    private final PriorityQueue<Boundary> queue = new PriorityQueue<>(
            Comparator.comparingLong(Boundary::atSeconds).thenComparing(Boundary::id));
    private long now;

    public SimulationTimeline(long nowSeconds, Collection<Boundary> persisted) {
        if (nowSeconds < 0) throw new IllegalArgumentException("Negative story time");
        now = nowSeconds;
        persisted.forEach(this::schedule);
    }
    public long nowSeconds() { return now; }
    public void schedule(Boundary boundary) {
        if (boundary.atSeconds() < now) throw new IllegalArgumentException("Boundary precedes story time");
        active.put(boundary.id(), boundary);
        queue.add(boundary);
    }
    public void cancel(String id) { active.remove(id); }
    public List<Boundary> snapshot() {
        return active.values().stream().sorted(queue.comparator()).toList();
    }
    /** Returns all events at the earliest boundary, not a winner ordered by worker completion. */
    public List<Boundary> next(long authorizedEnd, OptionalLong unresolvedAt) {
        if (authorizedEnd < now) throw new IllegalArgumentException("Authorization precedes story time");
        if (unresolvedAt.isPresent() && unresolvedAt.getAsLong() < now)
            throw new IllegalArgumentException("Unresolved boundary was already crossed");
        while (!queue.isEmpty() && active.get(queue.peek().id()) != queue.peek()) queue.remove();
        if (queue.isEmpty() || queue.peek().atSeconds() > authorizedEnd) return List.of();
        long at = queue.peek().atSeconds();
        // Equality is also blocked: the unresolved group must be restored before processing that instant.
        if (unresolvedAt.isPresent() && at >= unresolvedAt.getAsLong()) return List.of();
        var result = new ArrayList<Boundary>();
        while (!queue.isEmpty() && queue.peek().atSeconds() == at) {
            Boundary boundary = queue.remove();
            if (active.get(boundary.id()) == boundary) {
                active.remove(boundary.id());
                result.add(boundary);
            }
        }
        now = at;
        return List.copyOf(result);
    }
}
