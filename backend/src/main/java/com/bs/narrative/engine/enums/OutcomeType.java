package com.bs.narrative.engine.enums;

/** 规则引擎锁定的剧情分支类型。对应 interaction_log.outcome_type。 */
public enum OutcomeType {
    ATTACKER_WIN("攻方胜", true),
    DEFENDER_WIN("守方胜", false),
    DRAW("平局", null),
    THIRD_PARTY_INTERVENE("第三方出手", null),
    TALISMAN_NEUTRALIZE("符咒抵消", false),
    BRIBE_ACCEPTED("贿赂成交", true),
    DEFLECTED("反噬偏转", false),
    EXPLOITATION_RESCUE("算计救援", true),
    NO_OP("无事发生", null),
    INVALID("无效输入", null);

    public final String zhLabel;
    /** true=攻方视角胜利；false=守方胜利；null=无明确胜方。 */
    public final Boolean attackerSideWins;

    OutcomeType(String zh, Boolean attackerSideWins) {
        this.zhLabel = zh;
        this.attackerSideWins = attackerSideWins;
    }
}
