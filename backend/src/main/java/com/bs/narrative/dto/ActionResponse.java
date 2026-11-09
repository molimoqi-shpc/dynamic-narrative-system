package com.bs.narrative.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** PlayView 前端渲染一回合结果的响应体。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResponse(
        /** 写 interaction_log 后返回的日志 id；演示端点由于未走事务 + 未 DB save，可能为 null。 */
        Long logId,

        /** 规则引擎裁决：EXPLOITATION_RESCUE / TALISMAN_NEUTRALIZE / ATTACKER_WIN / … */
        String outcomeType,
        /** 裁决中文标签（来自 OutcomeType.zhLabel）。 */
        String outcomeLabel,
        /** 真正出手的第三方（可能 null）。 */
        String intervenerId,

        /** Pass2 生成的每个神明一条对白 + 内心（MVP 接入 LLM 前，这里先给出模板占位）。 */
        List<NarrativeLine> lines,

        /** 谎徽标：godId → true=该神明公开台词与内心/事实矛盾。 */
        Map<String, Boolean> lieFlags,

        /** 规则引擎产生的事实中文清单（直接塞给天眼面板“裁决理由”Tab）。 */
        List<String> factsChinese,

        /** 规则快照（能力/意愿/利益 + 差值 + delta），前端天眼面板用。 */
        Map<String, Object> ruleSnapshot,

        /** token 四项（MVP 纯规则阶段填 0 / 0，等接入 LLM Provider 后再回写真实值）。 */
        TokenBudget token,

        /** Phase 8：本回合后被推进的目标列表（前端目标面板刷新用）。 */
        List<ObjectiveBrief> objectives,

        /** Phase 10：本回合 NPC 群戏决策列表（前端群戏渲染用，与 lines 互补）。 */
        List<NpcDecisionBrief> npcDecisions
) {
    /** 兼容旧构造器：未带 objectives/npcDecisions 字段时填 null。 */
    public ActionResponse(Long logId, String outcomeType, String outcomeLabel,
                          String intervenerId, List<NarrativeLine> lines,
                          Map<String, Boolean> lieFlags, List<String> factsChinese,
                          Map<String, Object> ruleSnapshot, TokenBudget token) {
        this(logId, outcomeType, outcomeLabel, intervenerId, lines, lieFlags,
                factsChinese, ruleSnapshot, token, null, null);
    }

    /** Phase 8 目标摘要（前端目标面板用）。 */
    public record ObjectiveBrief(
            String objectiveId,
            String title,
            String status,
            Integer progress,
            String currentStep,
            /** 本回合是否被推进（true=前端高亮）。 */
            Boolean advanced
    ) {}

    /** Phase 10 NPC 决策摘要（前端群戏渲染用）。 */
    public record NpcDecisionBrief(
            String godId,
            String behavior,
            String speech,
            String innerThought,
            Boolean isLying
    ) {}
    public record NarrativeLine(
            String godId,
            /** 限知视角：公开台词。 */
            String speech,
            /** 全知视角：真实内心活动（天眼面板才显示）。 */
            String innerThought
    ) {}

    public record TokenBudget(
            Integer input, Integer output, Integer total, BigDecimal costCny
    ) {
        public static TokenBudget zero() {
            return new TokenBudget(0, 0, 0, BigDecimal.ZERO);
        }
    }
}
