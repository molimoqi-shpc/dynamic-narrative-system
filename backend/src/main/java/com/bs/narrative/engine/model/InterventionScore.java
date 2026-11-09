package com.bs.narrative.engine.model;

/** 第三方神明三角评分：能力×0.4 + 意愿×0.3 + 利益×0.3。字段均≥0，total 可能到 0~100。 */
public record InterventionScore(
        String intervenerId,
        String supportsGodId,   // 本次出手支持哪一方（actor 或 target）
        double ability,
        double will,
        double gain,
        double total,
        /** 中文说明：如“博物神信任雨神 30 且曾背刺，意愿值增加”。 */
        String noteZh) {

    public String shortSummary() {
        return String.format("%s→支持%s(能力=%.1f,意愿=%.1f,利益=%.1f,总分=%.1f)",
                intervenerId, supportsGodId, ability, will, gain, total);
    }
}
