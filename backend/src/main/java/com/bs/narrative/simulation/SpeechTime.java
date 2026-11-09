package com.bs.narrative.simulation;

/** Configurable initial pace, independent of network duration. No tick-to-minute conversion. */
public final class SpeechTime {
    private SpeechTime() {}
    public static long seconds(String text, double unitsPerSecond) {
        if (!Double.isFinite(unitsPerSecond) || unitsPerSecond<=0) throw new IllegalArgumentException("Invalid speech rate");
        long units=text.codePoints().filter(Character::isLetterOrDigit).count();
        return units==0 ? 0 : Math.max(1,(long)Math.ceil(units/unitsPerSecond));
    }
}
