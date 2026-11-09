package com.bs.narrative.engine.model;

import com.bs.narrative.engine.enums.StatKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** 六维数值增减量（回合结束施加到 base 属性上）。 */
public record StatDelta(
        int zhi, int hui, int dou, int wen, int ben, int gui) {

    public static final StatDelta ZERO = new StatDelta(0,0,0,0,0,0);

    public int of(StatKey k) {
        return switch (k) {
            case ZHI -> zhi; case HUI -> hui; case DOU -> dou;
            case WEN -> wen; case BEN -> ben; case GUI -> gui;
        };
    }

    public StatDelta plus(StatDelta other) {
        return new StatDelta(
                zhi + other.zhi, hui + other.hui, dou + other.dou,
                wen + other.wen, ben + other.ben, gui + other.gui);
    }

    public Map<String, Integer> asMap() {
        Map<String,Integer> m = new LinkedHashMap<>();
        for (StatKey k : StatKey.values()) m.put(k.name().toLowerCase(), of(k));
        return m;
    }

    public boolean isZero() {
        return zhi == 0 && hui == 0 && dou == 0 && wen == 0 && ben == 0 && gui == 0;
    }
}
