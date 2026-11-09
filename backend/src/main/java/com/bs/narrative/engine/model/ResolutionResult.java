package com.bs.narrative.engine.model;

import com.bs.narrative.engine.enums.OutcomeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 规则引擎最终裁决。所有字段不可变，纯函数返回。 */
public record ResolutionResult(
        OutcomeType outcome,
        /** 可以为 null：DRAW/NO_OP/INVALID 时无胜者。 */
        String winnerGodId,
        String loserGodId,
        /** 真正出手的第三方（可能 null）。 */
        InterventionScore intervener,
        /** 每神明的属性增量映射（godId → StatDelta）。 */
        Map<String, StatDelta> statDeltaPerGod,
        /** 每对有向关系的信任增减（key → +delta）。 */
        Map<StringPair, Integer> relationshipDeltaPerPair,
        /** 本回合真正消耗的物品（从背包移除）。 */
        List<String> consumedItems,
        /** 中文裁决一句摘要（用于日志 + FactsChineseBuilder）。 */
        String noteZh) {

    public ResolutionResult {
        if (outcome == null) outcome = OutcomeType.NO_OP;
        if (statDeltaPerGod == null) statDeltaPerGod = Collections.emptyMap();
        if (relationshipDeltaPerPair == null) relationshipDeltaPerPair = Collections.emptyMap();
        if (consumedItems == null) consumedItems = List.of();
        if (noteZh == null) noteZh = "";
    }
}
