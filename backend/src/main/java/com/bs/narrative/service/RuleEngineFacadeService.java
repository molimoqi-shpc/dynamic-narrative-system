package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.engine.*;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.OutcomeType;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则引擎唯一 Spring Service：组合 7 个纯函数 + 已有的 JPA Repository。
 * MVP 阶段**不注入真实 Repository**（先做“无 DB 的纯组合层”），给 DemoController 喂种子数据；
 * 后续 T8 再引入真实 JPA 注入 + 事务 + InteractionLog 落盘。
 */
@Service
public class RuleEngineFacadeService {

    /**
     * 演示：直接传入 Map / List 形式的种子数据（不需要数据库），
     * 返回完整 ActionResponse 给前端 PlayView 先做骨架渲染。
     */
    public ActionResponse resolveWithSeeds(ActionRequest req,
                                           GameScene scene,
                                           Map<String, GodProfile> godsById,
                                           List<GodRelationship> relationships) {
        // 1) 从请求里解析 Pass1Intent（MVP 从 structuredIntent 构造；未来由 Pass1Service 解析 playerText）
        Pass1Intent p1 = toPass1Intent(req);

        // 2) 在场神明 ID（含 player）
        Set<String> presentIds = new LinkedHashSet<>(scene.getPresentGods() == null ? List.of() : scene.getPresentGods());
        if (req.playerId() != null) presentIds.add(req.playerId());

        // 3) 实际六维
        Map<String, ActualStats> actuals = godsById.entrySet().stream()
                .filter(e -> presentIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> StatsCalculator.calcActualStats(e.getValue())));

        // 4) 意图校验
        ValidationResult vr = IntentValidator.validate(req.playerId(), p1, scene, godsById);
        if (!vr.isValid()) {
            return buildError(p1, OutcomeType.INVALID,
                    "意图校验失败：" + String.join("；", vr.reasons()), actuals);
        }

        // 5) 第三方三角评分
        // defender 直接取 p1（toPass1Intent 已保证非空：si=null 时 target="" 走 UNKNOWN 分支）
        String defender = p1.targetGodId();
        List<InterventionScore> interveners = InterventionScorer.scoreAll(
                p1, p1.actorGodId(), defender,
                scene.getPresentGods(), actuals, relationships);

        // 6) 冲突裁决
        ResolutionResult resolution = ConflictResolver.resolve(
                p1, req.playerId(), defender, godsById, actuals, interveners, relationships);

        // 7) Delta 施加（演示阶段只计算，不 save 到 DB）
        DeltaApplier.Snapshot snap = DeltaApplier.applyAll(resolution, godsById, relationships);
        Map<String, ActualStats> actualsAfter = DeltaApplier.actualsAfter(snap);

        // 8) 事实中文（godNames 可选：先从 godsById.name 填充）
        Map<String, String> godNames = new HashMap<>();
        for (GodProfile g : godsById.values()) godNames.put(g.getGodId(), g.getGodName());

        List<String> facts = FactsChineseBuilder.build(p1, actuals, resolution, interveners, godNames);

        // 10) MVP 占位对白：用事实做极简模板渲染（Pass2 接入 DeepSeek 后再换成真实 LLM 对白）
        List<ActionResponse.NarrativeLine> lines = buildPlaceholderLines(p1, resolution, facts);
        // 同一神明可能在 lines 中出现多次（如 EXPLOITATION_RESCUE：博物神同时是 winner 和 intervener），
        // 任一条台词被判谎 → 整体标 true（用 OR 合并函数避免 Collectors.toMap duplicate key 抛异常）。
        Map<String, Boolean> lieFlags = lines.stream().collect(Collectors.toMap(
                ActionResponse.NarrativeLine::godId,
                l -> LieDetector.isContradiction(l.speech(), facts, l.innerThought()),
                (a, b) -> a || b
        ));

        // 10) 规则快照（天眼面板）
        Map<String, Object> ruleSnapshot = buildRuleSnapshot(p1, actuals, actualsAfter, resolution, interveners);

        return new ActionResponse(
                null,
                resolution.outcome().name(),
                resolution.outcome().zhLabel,
                resolution.intervener() == null ? null : resolution.intervener().intervenerId(),
                lines,
                lieFlags,
                facts,
                ruleSnapshot,
                ActionResponse.TokenBudget.zero()
        );
    }

    // ================== 内部工具 ==================

    private Pass1Intent toPass1Intent(ActionRequest req) {
        ActionRequest.StructuredIntent si = req.structuredIntent();
        if (si == null) {
            return Pass1Intent.ofSimple(IntentEnum.OTHER, req.playerId(), "");
        }
        IntentEnum intent = IntentEnum.safeValueOf(si.intent(), IntentEnum.OTHER);
        String actor = (si.actorGodId() != null && !si.actorGodId().isBlank()) ? si.actorGodId() : req.playerId();
        String target = si.targetGodId() != null ? si.targetGodId() : "";
        StatKey k = StatKey.fromPass1Key(si.attackStatKey());
        double conf = si.confidence() == null ? 1.0 : Math.max(0.0, Math.min(1.0, si.confidence()));
        return new Pass1Intent(intent, actor, target,
                si.argument() != null ? si.argument() : (req.playerText() != null ? req.playerText() : ""),
                k, si.consumedItemOrTalisman(), conf, "");
    }

    private ActionResponse buildError(Pass1Intent p1, OutcomeType type, String note, Map<String, ActualStats> actuals) {
        return new ActionResponse(
                null, type.name(), type.zhLabel, null, List.of(), Map.of(),
                List.of(note),
                Map.of("p1_actor", p1.actorGodId(), "p1_target", p1.targetGodId()),
                ActionResponse.TokenBudget.zero());
    }

    /**
     * 占位对白：仅用于无 LLM 的演示端点（EngineDemoController）和单测。
     * 游戏主流程（GameActionService.processActionWithDb/moveScene/moveByCoord）
     * 已接入 Pass2Service，用规则结果(factsChinese)作为约束交给 LLM 生成真实叙事，
     * 返回前端的 lines 由 LLM 生成，不会用到这里的模板。
     */
    private List<ActionResponse.NarrativeLine> buildPlaceholderLines(Pass1Intent p1,
                                                                      ResolutionResult r,
                                                                      List<String> facts) {
        List<ActionResponse.NarrativeLine> lines = new ArrayList<>();
        // 玩家台词
        lines.add(new ActionResponse.NarrativeLine(
                p1.actorGodId(),
                "[模板演示] 玩家：「" + empty(p1.argument()) + "」",
                String.format("[内心] 裁决 %s 说明：%s", r.outcome().zhLabel, empty(r.noteZh()))
        ));
        // 败方：若败方≠玩家（即玩家赢），加一句失败者台词（含谎徽标测试：败方说"我没输"就矛盾）
        if (r.loserGodId() != null && !r.loserGodId().equals(p1.actorGodId())) {
            lines.add(new ActionResponse.NarrativeLine(r.loserGodId(),
                    "[模板演示] 败者：「唉，技不如人……我没出手」",
                    "[内心] 其实我被算计了。"));
        }
        // 胜方
        if (r.winnerGodId() != null) {
            lines.add(new ActionResponse.NarrativeLine(r.winnerGodId(),
                    "[模板演示] 胜者：「承让。」",
                    "[内心] 这回合在我掌握中。"));
        }
        // 真正出手第三方的"谎徽标测试台词"——博物馆表面帮雨神，内心想背刺，且台词否认介入 → lie=true
        if (r.intervener() != null) {
            lines.add(new ActionResponse.NarrativeLine(r.intervener().intervenerId(),
                    "[模板演示] 博物神：「我没出手，此事与我无关。」",
                    "[内心] 我早就看准了雨神信任 30 + backstab=1，正好救援捞一笔！"));
        }
        return List.copyOf(lines);
    }

    private Map<String, Object> buildRuleSnapshot(Pass1Intent p1,
                                                   Map<String, ActualStats> before,
                                                   Map<String, ActualStats> after,
                                                   ResolutionResult r,
                                                   List<InterventionScore> interveners) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("formula_version",StatsCalculator.FORMULA_VERSION);
        snap.put("relative_win_rate",ConflictResolver.RELATIVE_WIN_RATE);
        snap.put("threshold_intervene", StatsCalculator.INTERVENE_THRESHOLD);
        snap.put("diff_clear_win", ConflictResolver.DIFF_CLEAR_WIN);
        snap.put("exploit_trust_min", ConflictResolver.EXPLOIT_TRUST_MIN);
        snap.put("before_actuals_per_god", before.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, e -> e.getValue().actualsAsMap())));
        snap.put("after_actuals_per_god",  after.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, e -> e.getValue().actualsAsMap())));
        if (!interveners.isEmpty()) {
            List<Map<String,Object>> list = new ArrayList<>();
            for (InterventionScore c : interveners) list.add(Map.of(
                    "intervenerId", c.intervenerId(), "supports", c.supportsGodId(),
                    "ability", c.ability(), "will", c.will(), "gain", c.gain(),
                    "total", c.total(), "noteZh", String.valueOf(c.noteZh())
            ));
            snap.put("interveners", list);
        }
        snap.put("statDeltaPerGod", r.statDeltaPerGod().entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, e -> e.getValue().asMap())));
        // Explain directed relationship changes using the computed result, not copied prose.
        snap.put("relationshipDeltas", r.relationshipDeltaPerPair().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().from + "->" + e.getKey().to))
                .map(e -> Map.<String,Object>of("sourceId", e.getKey().from,
                        "targetId", e.getKey().to, "delta", e.getValue())).toList());
        snap.put("consumedItems", r.consumedItems());
        snap.put("resolution_note", r.noteZh());
        return Map.copyOf(snap);
    }

    private static String empty(String s) { return s == null ? "" : s; }
}
