package com.bs.narrative.engine.model;

import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.StatKey;

/** Pass1 抽出的结构化意图。若 attackStatKey 为 null，则 IntentValidator 会按 preferred 默认补齐。 */
public record Pass1Intent(
        IntentEnum intent,
        String actorGodId,
        String targetGodId,
        /** 对意图的一句自然语言补充说明（玩家原话的摘要），可为空串。 */
        String argument,
        /** 选定的对抗属性（null 代表未指定）。 */
        StatKey attackStatKey,
        /** CONSUME_ITEM / CAST_TALISMAN 时引用的物品名，可为 null。 */
        String consumedItemOrTalisman,
        /** Pass1 置信度 ∈ [0,1]，0 代表 LLM 自己都不确定。 */
        double confidence,
        /** Pass1 原始 JSON（留底）。 */
        String rawJson) {

    public Pass1Intent {
        if (intent == null) intent = IntentEnum.OTHER;
        if (actorGodId == null) actorGodId = "";
        if (targetGodId == null) targetGodId = "";
        if (argument == null) argument = "";
        if (rawJson == null) rawJson = "";
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static Pass1Intent ofSimple(IntentEnum intent, String actor, String target) {
        return new Pass1Intent(intent, actor, target, "", null, null, 1.0, "");
    }
}
