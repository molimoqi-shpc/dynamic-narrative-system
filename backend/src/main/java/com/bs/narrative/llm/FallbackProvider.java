package com.bs.narrative.llm;

import java.util.List;
import java.util.Map;

/**
 * 防坑规则2 兜底：LLM 彻底失败（超时 / 解析失败 / API 401）时回退到本地预设脚本，
 * 保证演示绝不白屏死机。
 * 输出符合 Pass1 协议的 UNKNOWN 意图 + 一段通用环境旁白，
 * 让 ConflictResolver 自动走 NO_OP 分支（与防坑规则1 链路汇合）。
 */
public final class FallbackProvider {

    /** 占位 cost（fallback 不花 token）。 */
    public static final double FALLBACK_COST = 0.0;

    private FallbackProvider() {}

    /**
     * Pass1 兜底：返回固定 UNKNOWN 意图 JSON。
     * 字段对齐 Pass1Service 期望的 schema：intent/actor/target/attackStatKey/itemId/playerText/confidence/rawInput。
     */
    public static String fallbackPass1Json(String playerText) {
        String safeText = escape(playerText == null ? "" : playerText);
        return """
                {
                  "intent": "UNKNOWN",
                  "actorGodId": "",
                  "targetGodId": "",
                  "attackStatKey": null,
                  "itemId": null,
                  "playerText": "%s",
                  "confidence": 0.0,
                  "rawInput": "FALLBACK_TRIGGERED"
                }""".formatted(safeText);
    }

    /** Pass2 兜底：返回通用环境旁白 JSON（displayedDialogue + truePsychologicalActivity + isLying=false）。 */
    public static String fallbackPass2Json(String playerText) {
        String safeText = escape(playerText == null ? "" : playerText);
        return """
                {
                  "displayedDialogue": "风穿古碑，雨师凭栏，万物寂然。诸神未发一言，唯水声潺潺。",
                  "truePsychologicalActivity": "本回合 LLM 调用失败，已回退至本地预设旁白。玩家输入：%s",
                  "isLying": false
                }""".formatted(safeText);
    }

    /** 模拟一次 0-token 的 LLMResponse（用于统一落盘流程）。 */
    public static LLMResponse fallbackResponse(String content, String parsedJson) {
        return new LLMResponse(content, parsedJson, 0, 0, 0, FALLBACK_COST);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
