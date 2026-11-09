package com.bs.narrative.engine.model;

import java.util.Objects;

/** 有向 source→target 键；重写 equals/hashCode，可作 Map key。 */
public final class StringPair {
    public final String from;
    public final String to;

    public StringPair(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public static StringPair of(String from, String to) { return new StringPair(from, to); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StringPair that)) return false;
        return Objects.equals(from, that.from) && Objects.equals(to, that.to);
    }
    @Override public int hashCode() { return Objects.hash(from, to); }
    @Override public String toString() { return from + "->" + to; }
}
