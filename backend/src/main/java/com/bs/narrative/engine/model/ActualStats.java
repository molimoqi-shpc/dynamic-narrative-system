package com.bs.narrative.engine.model;

import com.bs.narrative.engine.enums.StatKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** V2 actual dimensions, retaining sanitized bases for checkpoints and transparent calculations. */
public record ActualStats(
        int baseZhi, int baseHui, int baseDou, int baseWen, int baseBen, int baseGui,
        int actualZhi, int actualHui, int actualDou, int actualWen, int actualBen, int actualGui) {

    public int actualOf(StatKey k) {
        return switch (k) {
            case ZHI -> actualZhi; case HUI -> actualHui; case DOU -> actualDou;
            case WEN -> actualWen; case BEN -> actualBen; case GUI -> actualGui;
        };
    }

    public int baseOf(StatKey k) {
        return switch (k) {
            case ZHI -> baseZhi; case HUI -> baseHui; case DOU -> baseDou;
            case WEN -> baseWen; case BEN -> baseBen; case GUI -> baseGui;
        };
    }

    public Map<String, Integer> actualsAsMap() {
        Map<String,Integer> m = new LinkedHashMap<>();
        for (StatKey k : StatKey.values()) m.put(k.name().toLowerCase(), actualOf(k));
        return m;
    }
    public Map<String,Integer> basesAsMap() {
        Map<String,Integer> values=new LinkedHashMap<>();
        for(StatKey key:StatKey.values()) values.put(key.pinyin,baseOf(key));
        return values;
    }
}
