package com.bs.narrative.llm;

/**
 * LLM 调用结果快照（包含 token 与成本，便于落盘 interaction_log.token_*）。
 *
 * @param content       原始文本（已剥离 markdown ```json 围栏）
 * @param parsedJson    LLMResponseParser 提取的纯 JSON 字符串；非 JSON 模式调用时为 null
 * @param tokenInput    本回合输入 token 数
 * @param tokenOutput   本回合输出 token 数
 * @param tokenTotal    总 tokens = input + output
 * @param costCny       本回合成本（元，CNY），按 LLMConfig.price-per-million 计算
 */
public record LLMResponse(
        String content,
        String parsedJson,
        int tokenInput,
        int tokenOutput,
        int tokenTotal,
        double costCny
) {
    public static LLMResponse raw(String content, int in, int out, double cost) {
        return new LLMResponse(content, null, in, out, in + out, cost);
    }
    public static LLMResponse json(String content, String parsed, int in, int out, double cost) {
        return new LLMResponse(content, parsed, in, out, in + out, cost);
    }
}
