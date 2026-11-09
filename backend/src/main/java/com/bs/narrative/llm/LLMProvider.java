package com.bs.narrative.llm;

import java.util.List;
import java.util.Map;

/**
 * 统一 LLM 调用接口（OpenAI 兼容协议，DeepSeek/通义/Kimi 都可适配）。
 * 调用方只关心：拿一串 messages（system/user/assistant），得到文本 + token 计费 + cost。
 * 实现方负责：HTTP 调用、JSON Mode 启用、重试、超时、异常包装。
 *
 * 双通道设计（主副分离）：
 *   - chatMain/chatMainForJson：主 LLM（v4-pro），核心叙事
 *   - chatSub/chatSubForJson：副 LLM（v4-flash），NPC 群戏等角色扮演
 *   - 默认 chat/chatForJson 走主通道（向后兼容）
 */
public interface LLMProvider {

    /**
     * 普通对话调用（无强制 JSON）。默认走主通道。
     */
    LLMResponse chat(List<Map<String, String>> messages);

    /**
     * 强制 JSON 模式调用（防坑规则2：结构化输出 / Json Mode）。
     * 实现必须把 response_format = {"type":"json_object"} 显式传给供应商；
     * 同时 content 已通过本地 LLMResponseParser 兜底清洗，确保可被 JSON 反序列化。
     * 默认走主通道。
     */
    LLMResponse chatForJson(List<Map<String, String>> messages);

    // ===== 主副双通道 =====

    /** 主 LLM 调用（v4-pro）。默认等同 chat()。 */
    default LLMResponse chatMain(List<Map<String, String>> messages) {
        return chat(messages);
    }

    /** 主 LLM JSON 模式（v4-pro）。默认等同 chatForJson()。 */
    default LLMResponse chatMainForJson(List<Map<String, String>> messages) {
        return chatForJson(messages);
    }

    /** 副 LLM 调用（v4-flash）。默认等同 chat()（单通道时退化）。 */
    default LLMResponse chatSub(List<Map<String, String>> messages) {
        return chat(messages);
    }

    /** 副 LLM JSON 模式（v4-flash）。默认等同 chatForJson()（单通道时退化）。 */
    default LLMResponse chatSubForJson(List<Map<String, String>> messages) {
        return chatForJson(messages);
    }

}
