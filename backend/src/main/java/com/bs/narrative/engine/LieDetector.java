package com.bs.narrative.engine;

import java.util.List;

/**
 * 说谎徽标判定（MVP 规则版，无需 LLM）：
 *   - 若 公开台词 明确出现“否认自己/他方动手”的措辞，而 factsChinese 明确有「第三方出手成功」「守方符咒抵消」等相反事实 → lie=true。
 *   - 若 内心活动 innerThought 与 factsChinese 的关键结论（如"背刺"）在关键词层面也冲突 → lie=true。
 *   - 其他默认 lie=false（即"不撒谎"，避免误标）。
 * 纯函数，可复现。Pass2 对白生成后立即调用，结果写到前端渲染的「谎徽标」字段。
 */
public final class LieDetector {

    private LieDetector() {}

    /** 台词里的“否定自我介入”典型措辞（任意命中一个 → 触发与 第三方出手事实 矛盾检查）。 */
    private static final List<String> DENY_ACTION_PHRASES = List.of(
            "我没出手", "我没有出手", "并未出手", "未曾出手",
            "我没帮", "我没有帮", "并未帮", "未曾帮",
            "我没介入", "没有介入", "并未介入", "未曾介入",
            "此事与我无关", "与我无关", "我不知道", "我不清楚", "我无计可施"
    );

    private static final List<String> THIRD_PARTY_SUCCEED_KEYWORDS = List.of(
            "第三方", "真正出手的第三方", "介入", "从旁出手", "救援", "算计救援", "EXPLOITATION_RESCUE",
            "THIRD_PARTY_INTERVENE"
    );

    private static final List<String> TALISMAN_NEUTRALIZE_KEYWORDS = List.of(
            "符咒抵消", "TALISMAN_NEUTRALIZE", "使用符类物品抵消", "止水符"
    );

    public static boolean isContradiction(String speech, List<String> factsChinese, String innerThought) {
        if (speech == null || speech.isBlank()) return false;
        List<String> facts = factsChinese == null ? List.of() : factsChinese;
        String joined = String.join("；", facts);

        // 规则 1：台词否认介入，但事实含“第三方出手成功”
        boolean thirdSuccess = THIRD_PARTY_SUCCEED_KEYWORDS.stream().anyMatch(joined::contains);
        boolean denyAction = DENY_ACTION_PHRASES.stream().anyMatch(speech::contains);
        if (denyAction && thirdSuccess) return true;

        // 规则 2：台词说“自己的符咒顶不住/没用”，但事实明说“符咒抵消成功”
        boolean saysFailedTalisman = List.of("符咒没用", "符没用", "抵不住", "扛不住", "挡不住")
                .stream().anyMatch(speech::contains);
        boolean factNeutralized = TALISMAN_NEUTRALIZE_KEYWORDS.stream().anyMatch(joined::contains);
        if (saysFailedTalisman && factNeutralized) return true;

        // 规则 3：内心活动若也和事实背道而驰（比如事实含有"背刺"、innerThought 里"我从未背叛"）→ lie=true
        if (innerThought != null && !innerThought.isBlank()) {
            boolean factSaysBackstab = joined.contains("背刺") || joined.contains("EXPLOITATION_RESCUE");
            boolean innerSaysLoyal = List.of("从未背叛", "不会背叛", "一心效忠", "毫无二心")
                    .stream().anyMatch(innerThought::contains);
            if (factSaysBackstab && innerSaysLoyal) return true;
            // 语义冲突：innerThought 和 speech 互相直接矛盾（简单关键词）
            boolean innerSaysDenied = DENY_ACTION_PHRASES.stream().anyMatch(innerThought::contains);
            if (thirdSuccess && innerSaysDenied) return true;
        }
        return false;
    }
}
