package com.bs.narrative.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PlayView 前端发起一回合行动的请求体（MVP 直接携带结构化意图；接入 Pass1 后改由后端解析 playerText）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionRequest(
        @NotBlank(message = "playerId 不能为空") String playerId,
        @NotBlank(message = "sceneId 不能为空")  String sceneId,
        /** 玩家自由文本（将来喂给 Pass1；MVP 阶段同时也从 structuredIntent 取就好）。 */
        @NotBlank @Size(max=4000) String playerText,
        /** MVP：前端或调用方直接提供结构化意图，跳过 Pass1 直接走规则。 */
        StructuredIntent structuredIntent,
        /** Explicit conversation UI: these are spoken words, never a private action plan. */
        @Valid DialogueInput dialogue
) {
    public ActionRequest(String playerId, String sceneId, String playerText, StructuredIntent structuredIntent) {
        this(playerId, sceneId, playerText, structuredIntent, null);
    }
    public record DialogueInput(
            @NotBlank @Size(max=128) String targetGodId,
            @NotBlank @Size(max=4000) String spokenText
    ) {}

    /** 结构化意图（轻量版，足以命中 Walkthrough）。 */
    public record StructuredIntent(
            /** ATTACK / PROVOKE / BRIBE / ... 对应 IntentEnum 大写名称。 */
            String intent,
            /** 发起神明 id（通常等于 playerId；NPC↔NPC 互动才不同）。 */
            String actorGodId,
            /** 目标神明 id。 */
            String targetGodId,
            /** 选中的对抗属性：zhi/hui/dou/wen/ben/gui 汉字或拼音，可 null。 */
            String attackStatKey,
            /** CONSUME_ITEM / CAST_TALISMAN 指定的物品名；可 null。 */
            String consumedItemOrTalisman,
            /** 0..1 置信度；可为 null 默认 1.0。 */
            Double confidence,
            /** 自然语言补充说明。 */
            String argument
    ) {}
}
