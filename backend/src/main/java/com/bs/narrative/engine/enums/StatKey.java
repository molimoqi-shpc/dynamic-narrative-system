package com.bs.narrative.engine.enums;

import java.util.Locale;
import java.util.Set;

/** 六维属性键：知/慧/斗/闻/本/归；历史输入别名仍兼容。 */
public enum StatKey {
    ZHI("知", "zhi", "zh"),
    HUI("慧", "hui", "h"),
    DOU("斗", "dou", "d"),
    WEN("闻", "wen", "w"),
    BEN("本", "ben", "b"),
    GUI("归", "gui", "g");

    public final String zhLabel;
    public final String pinyin;
    public final String shorthand;

    StatKey(String zhLabel, String pinyin, String shorthand) {
        this.zhLabel = zhLabel;
        this.pinyin = pinyin;
        this.shorthand = shorthand;
    }

    /** 接受 Pass1 可能的 key 变体：拼音/汉字/缩写。未知返回 null。 */
    public static StatKey fromPass1Key(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        for (StatKey k : values()) {
            if (s.equals(k.pinyin) || s.equals(k.name().toLowerCase(Locale.ROOT))
                    || s.equals(k.shorthand) || raw.equals(k.zhLabel)) return k;
        }
        if (Set.of("zhi","智","知","zh").contains(s)) return ZHI;
        if (Set.of("hui","慧","h").contains(s) || raw.equals("慧")) return HUI;
        if (Set.of("dou","斗","d","do").contains(s) || raw.equals("斗")) return DOU;
        if (Set.of("wen","文","闻","w").contains(s)) return WEN;
        if (Set.of("ben","本","b").contains(s) || raw.equals("本")) return BEN;
        if (Set.of("gui","诡","归","g","gu").contains(s)) return GUI;
        return null;
    }
}
