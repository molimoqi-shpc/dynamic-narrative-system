package com.bs.narrative.engine.enums;

import java.util.List;

/** Pass1 输出意图白名单（OTHER 兜底；UNKNOWN 用于玩家废话/恶意注入，走通用旁白）。 */
public enum IntentEnum {
    ATTACK(      "武力攻击",   List.of("DOU","GUI")),
    PROVOKE(     "挑衅",       List.of("WEN","DOU")),
    ALLIANCE(    "结盟",       List.of("WEN","ZHI")),
    BRIBE(       "贿赂",       List.of("GUI","WEN")),
    THREAT(      "威逼",       List.of("DOU","GUI")),
    DEFEND(      "防御",       List.of("DOU","ZHI")),
    QUESTION(    "诘问",       List.of("HUI","WEN")),
    WAIT(        "等待时机", List.of()),
    RESCUE(      "接应救援", List.of()),
    GIVE(        "赠予物品", List.of()),
    TRADE(       "交换物品", List.of()),
    SHARE_INFO(  "提供消息", List.of()),
    OBSERVE(     "观察",       List.of("HUI","ZHI")),
    CONSUME_ITEM("使用物品",   List.of("WEN")),
    CAST_TALISMAN("祭符/施咒", List.of("GUI")),
    NARRATE(     "叙述/旁白",  List.of("WEN")),
    // —— 积木式场景引擎：多场景移动 + 探查 + 对话 + 拾取（无对抗属性，走 NO_OP/旁白分支） ——
    MOVE(        "移动到其他场景", List.of()),
    EXAMINE(     "观察场景细节", List.of()),
    PICK_UP(     "拾取物品",    List.of()),
    TALK(        "纯对话无对抗", List.of()),
    /** 玩家输入无关废话或恶意注入时，Pass1 应识别为 UNKNOWN；本地引擎跳过校验，走通用环境旁白。 */
    UNKNOWN(     "未知",       List.of()),
    OTHER(       "其他",       List.of("HUI"));

    public final String zhLabel;
    /** 优先对抗属性键名，按 StatKey.name() 大写。 */
    public final List<String> preferredAttackStatKeys;

    IntentEnum(String zhLabel, List<String> keys) {
        this.zhLabel = zhLabel;
        this.preferredAttackStatKeys = List.copyOf(keys);
    }

    /** 若 p1 指定的 attackStatKey 不被当前意图支持，则自动回落到第一个 preferred。UNKNOWN 无 preferred → 返回 null。 */
    public String firstAttackKey() { return preferredAttackStatKeys.isEmpty() ? null : preferredAttackStatKeys.get(0); }

    public boolean supportsKey(String keyName) {
        return preferredAttackStatKeys.stream().anyMatch(k -> k.equalsIgnoreCase(keyName));
    }

    public static IntentEnum safeValueOf(String raw, IntentEnum fallback) {
        if (raw == null) return fallback;
        try { return IntentEnum.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException iae) { return fallback; }
    }
}
