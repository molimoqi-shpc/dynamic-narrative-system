package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.OutcomeType;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.ActualStats;
import com.bs.narrative.engine.model.InterventionScore;
import com.bs.narrative.engine.model.Pass1Intent;
import com.bs.narrative.engine.model.ResolutionResult;
import com.bs.narrative.engine.model.StatDelta;
import com.bs.narrative.engine.model.StringPair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 5 层优先级冲突裁决（从高到低，命中即短路）：
 *   P1 符咒抵消：防守方 hasCategoryTalisman 且 intent ∈ {ATTACK, THREAT, PROVOKE}
 *   P2 背刺反转：干预出手者且 干预者→defender backstab=1 & trust≥30 → EXPLOITATION_RESCUE（博物神经典分支）
 *   P3 第三方干预：interveners 非空 → THIRD_PARTY_INTERVENE，winners 改为 支持方
 *   P4 属性差对抗：T=max(10,round(0.10*(双方实际值之和)))，|diff|≥T 判胜负，否则 DRAW
 *   P5 意图特殊路径：
 *          BRIBE  → defender.actualBen ≥ 40 → BRIBE_ACCEPTED
 *          OBSERVE / QUESTION / NARRATE / OTHER → NO_OP（无数值变化，只给旁白）
 */
public final class ConflictResolver {

    public static final int DIFF_CLEAR_WIN = 10;
    public static final double RELATIVE_WIN_RATE = 0.10;
    public static int clearWinThreshold(int actorActual,int defenderActual) {
        return Math.max(DIFF_CLEAR_WIN,(int)Math.round(RELATIVE_WIN_RATE
                *((double)Math.max(0,actorActual)+Math.max(0,defenderActual))));
    }
    /** 触发 EXPLOITATION_RESCUE 所需最小 trust */
    public static final int EXPLOIT_TRUST_MIN = 30;
    /** 贿赂成交最低 defender.actualBen */
    public static final int BRIBE_ACCEPT_BEN_MIN = 40;

    private ConflictResolver() {}

    // 快捷：构造空的 delta 与 relationship delta，并填入一条变动 -----------------------------------------------------------------------
    private static Map<String, StatDelta> newGodDeltaMap() { return new LinkedHashMap<>(); }
    private static void plusDelta(Map<String, StatDelta> m, String godId, StatDelta d) {
        m.merge(godId, d, StatDelta::plus);
    }
    private static Map<StringPair, Integer> newRelDeltaMap() { return new LinkedHashMap<>(); }
    private static void plusRel(Map<StringPair, Integer> m, String from, String to, int delta) {
        m.merge(StringPair.of(from, to), delta, Integer::sum);
    }

    /**
     * 统一入口。
     *
     * @param p1          结构化意图（已通过 IntentValidator）
     * @param playerId    玩家 id（通常 actor=playerId；对 NPC↔NPC 可能不使用）
     * @param defenderId  防守方 = p1.targetGodId（传进来只是省略再取字段）
     * @param godsById    godId → GodProfile（至少 actor + defender + interveners）
     * @param actuals     godId → ActualStats
     * @param interveners InterventionScorer 输出的 ≥ 阈值且降序的列表
     * @param rels        所有在场神明的有向关系（背刺判定需要 干预者→defender）
     */
    public static ResolutionResult resolve(Pass1Intent p1,
                                           String playerId,
                                           String defenderId,
                                           Map<String, GodProfile> godsById,
                                           Map<String, ActualStats> actuals,
                                           List<InterventionScore> interveners,
                                           List<GodRelationship> rels) {
        if (p1 == null) {
            return new ResolutionResult(OutcomeType.INVALID, null, null, null,
                    Map.of(), Map.of(), List.of(), "Pass1 为空");
        }
        String actor = p1.actorGodId();
        String target = (defenderId == null || defenderId.isBlank()) ? p1.targetGodId() : defenderId;
        IntentEnum intent = p1.intent();
        StatKey attackKey = p1.attackStatKey() != null ? p1.attackStatKey()
                : StatKey.fromPass1Key(intent.firstAttackKey());
        if (attackKey == null) attackKey = StatKey.DOU;

        Map<String, StatDelta> stats = newGodDeltaMap();
        Map<StringPair, Integer> relsD = newRelDeltaMap();
        List<String> consumed = new ArrayList<>();
        // Informational or unrecognized input must never trigger combat through a third party.
        if (java.util.Set.of(IntentEnum.UNKNOWN,IntentEnum.OBSERVE,IntentEnum.EXAMINE,IntentEnum.QUESTION,
                IntentEnum.WAIT,IntentEnum.RESCUE,IntentEnum.GIVE,IntentEnum.TRADE,IntentEnum.SHARE_INFO,IntentEnum.NARRATE,IntentEnum.DEFEND,IntentEnum.ALLIANCE,IntentEnum.OTHER,IntentEnum.TALK,
                IntentEnum.MOVE,IntentEnum.PICK_UP).contains(intent)) {
            return build(OutcomeType.NO_OP,null,null,null,stats,relsD,consumed,
                    intent==IntentEnum.UNKNOWN ? "玩家输入未被识别为有效意图，不触发数值变动；仅依据当前场景描述环境，不新增人物或地点。"
                            : "非对抗行为，不改变战斗属性");
        }
        String note = "";
        OutcomeType outcome;
        String winner = null;
        String loser = null;
        InterventionScore topIntervener = (interveners != null && !interveners.isEmpty()) ? interveners.get(0) : null;

        // ======== 裁决优先级（从高到低：高阶博弈 > 物品抵消 > 第三方普通支持 > 属性差对抗 > 意图特殊路径）====

        // ——— P2 背刺反转（最高优先级）：存在出手第三方且 干预者→target 背刺 + trust≥EXPLOIT_TRUST_MIN
        //     神明级别的算计优先于一切符咒/属性差——第三方会抢在守方祭符之前完成介入（甚至掉包符），
        //     保证 Walkthrough（丰都 rain 有止水符 + museum 背刺 trust30=1）仍命中 EXPLOITATION_RESCUE。
        if (topIntervener != null) {
            String interv = topIntervener.intervenerId();
            GodRelationship backstabRel = findRel(rels, interv, target);
            boolean backstab = backstabRel != null && Boolean.TRUE.equals(backstabRel.getBackstabbed());
            int trust = backstabRel != null && backstabRel.getTrustValue() != null ? backstabRel.getTrustValue() : 0;
            if (backstab && trust >= EXPLOIT_TRUST_MIN) {
                outcome = OutcomeType.EXPLOITATION_RESCUE;
                winner = interv;
                loser  = actor;
                plusDelta(stats, interv, new StatDelta(0,0,0,2,0,1));
                plusDelta(stats, target, new StatDelta(0,0,1,0,0,0));
                plusDelta(stats, actor,  new StatDelta(0,0,0,-2,0,0));
                plusRel(relsD, interv, target, 10);
                plusRel(relsD, actor, interv, -8);
                plusRel(relsD, actor, target, -3);
                note = String.format("[算计救援]%s 以“帮%s”为明面实则背刺得利，%s 被反制。trust(%s)=%d ≥ %d，backstab=1。",
                        interv, target, actor, interv+"→"+target, trust, EXPLOIT_TRUST_MIN);
                return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
            }
        }

        // ——— P1 符咒抵消（次优先）：防守方无背刺第三方加持时，靠自带符类顶掉攻击类攻势
        GodProfile defenderProfile = godsById == null ? null : godsById.get(target);
        boolean defenderHasTalisman = InventoryMatcher.hasCategoryTalisman(defenderProfile);
        boolean attackLike = (intent == IntentEnum.ATTACK || intent == IntentEnum.THREAT || intent == IntentEnum.PROVOKE);
        if (defenderHasTalisman && attackLike) {
            outcome = OutcomeType.TALISMAN_NEUTRALIZE;
            winner  = target;
            loser   = actor;
            if (defenderProfile != null && defenderProfile.getInventory() != null) {
                for (String it : defenderProfile.getInventory()) {
                    if (it != null && InventoryMatcher.TALISMAN_KEYWORDS.stream().anyMatch(it::contains)) {
                        consumed.add(it);
                        break;
                    }
                }
            }
            plusRel(relsD, actor, target, -2);
            note = String.format("守方[%s]使用符类物品抵消了%s攻势", target, intent.zhLabel);
            return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
        }

        // ——— P3 第三方出手（没背刺的普通支持） ———
        if (topIntervener != null) {
            outcome = OutcomeType.THIRD_PARTY_INTERVENE;
            String side = topIntervener.supportsGodId();
            winner = side;
            loser  = side.equals(target) ? actor : target;
            plusDelta(stats, topIntervener.intervenerId(), new StatDelta(0,0,0,1,0,1));
            plusDelta(stats, winner, new StatDelta(0,0,1,1,0,0));
            plusDelta(stats, loser,  new StatDelta(0,0,-2,-1,0,0));
            plusRel(relsD, topIntervener.intervenerId(), side, 5);
            plusRel(relsD, actor, target, -3);
            note = String.format("第三方%s介入，支持%s → 胜方 %s",
                    topIntervener.intervenerId(), side, winner);
            return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
        }

        // ——— P4 属性差对抗 ———
        ActualStats aAc = actuals == null ? null : actuals.get(actor);
        ActualStats dAc = actuals == null ? null : actuals.get(target);
        if (aAc != null && dAc != null && attackLike) {
            int diff = StatsCalculator.statDifference(aAc, dAc, attackKey);
            int threshold=clearWinThreshold(aAc.actualOf(attackKey),dAc.actualOf(attackKey));
            if (diff >= threshold) {
                outcome = OutcomeType.ATTACKER_WIN; winner = actor; loser = target;
                plusDelta(stats, actor,  new StatDelta(0,0,2,1,0,0));
                plusDelta(stats, target, new StatDelta(0,0,-2,-1,0,0));
                plusRel(relsD, actor, target, -5);
                note = String.format("%s攻方[%s] 实际%s=%d 对 守方[%s] %s=%d，差值=%d ≥ %d → 攻方胜",
                        intent.zhLabel, actor, attackKey.zhLabel, aAc.actualOf(attackKey),
                        target, attackKey.zhLabel, dAc.actualOf(attackKey), diff, threshold);
            } else if (diff <= -threshold) {
                outcome = OutcomeType.DEFENDER_WIN; winner = target; loser = actor;
                plusDelta(stats, target, new StatDelta(0,0,2,1,0,0));
                plusDelta(stats, actor,  new StatDelta(0,0,-2,-1,0,0));
                plusRel(relsD, target, actor, -5);
                note = String.format("%s守方[%s] %s=%d 碾压 攻方[%s] %s=%d，差值=%d ≤ -%d → 守方胜",
                        intent.zhLabel, target, attackKey.zhLabel, dAc.actualOf(attackKey),
                        actor, attackKey.zhLabel, aAc.actualOf(attackKey), diff, threshold);
            } else {
                outcome = OutcomeType.DRAW; winner = null; loser = null;
                plusDelta(stats, actor,  new StatDelta(0,0,-1,0,0,0));
                plusDelta(stats, target, new StatDelta(0,0,-1,0,0,0));
                plusRel(relsD, actor, target, -1);
                note = String.format("双方实力接近（差值=%d，阈值±%d）→ 平局", diff, threshold);
            }
            return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
        }

        // ——— P5 意图特殊路径 / NO_OP / 观察/诘问/OTHER ———
        switch (intent) {
            case BRIBE: {
                ActualStats def = actuals == null ? null : actuals.get(target);
                int ben = def != null ? def.actualBen() : 0;
                if (ben >= BRIBE_ACCEPT_BEN_MIN) {
                    outcome = OutcomeType.BRIBE_ACCEPTED; winner = actor; loser = target;
                    plusDelta(stats, actor, new StatDelta(0,0,0,0,0,1));
                    plusDelta(stats, target, new StatDelta(0,0,0,0,-1,0));
                    plusRel(relsD, actor, target, 8);
                    note = String.format("守方本=%d ≥ %d，受贿成立", ben, BRIBE_ACCEPT_BEN_MIN);
                } else {
                    outcome = OutcomeType.DEFENDER_WIN; winner = target; loser = actor;
                    plusRel(relsD, actor, target, -4);
                    note = String.format("守方本=%d < %d，不接受贿赂", ben, BRIBE_ACCEPT_BEN_MIN);
                }
                return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
            }
            case CONSUME_ITEM:
            case CAST_TALISMAN: {
                String item = InventoryMatcher.resolveActualItem(godsById == null ? null : godsById.get(actor),
                        p1.consumedItemOrTalisman());
                if (item != null) consumed.add(item);
                outcome = OutcomeType.ATTACKER_WIN; winner = actor; loser = target;
                plusDelta(stats, actor, new StatDelta(0,0,0,0,0,1));
                plusDelta(stats, target, new StatDelta(0,0,-1,0,0,-1));
                plusRel(relsD, actor, target, -2);
                note = String.format("%s 使用 [%s]", intent.zhLabel, item != null ? item : "未知物品");
                return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
            }
            case OBSERVE:
            case QUESTION:
            case NARRATE:
            case DEFEND:
            case ALLIANCE:
            case UNKNOWN:
                // UNKNOWN（玩家废话/恶意注入）：触发通用环境氛围描写，无数值变动，绝不白屏。
                outcome = OutcomeType.NO_OP;
                winner = null; loser = null;
                note = IntentEnum.UNKNOWN.equals(intent)
                        ? "玩家输入未被识别为有效意图，不触发数值变动；仅依据当前场景描述环境，不新增人物或地点。"
                        : String.format("意图[%s]为非对抗型，不触发数值变动", intent.zhLabel);
                return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
            case OTHER:
            default:
                outcome = OutcomeType.NO_OP;
                winner = null; loser = null;
                note = String.format("意图[%s]为非对抗型，不触发数值变动", intent.zhLabel);
                return build(outcome, winner, loser, topIntervener, stats, relsD, consumed, note);
        }
    }

    // ------------------------------------------------------------------
    private static GodRelationship findRel(List<GodRelationship> rels, String from, String to) {
        if (rels == null) return null;
        for (GodRelationship r : rels) {
            if (r == null) continue;
            if (from.equals(r.getSourceId()) && to.equals(r.getTargetId())) return r;
        }
        return null;
    }

    private static ResolutionResult build(OutcomeType o, String w, String l, InterventionScore ti,
                                          Map<String, StatDelta> s, Map<StringPair, Integer> r,
                                          List<String> c, String note) {
        return new ResolutionResult(o, w, l, ti,
                s == null ? Map.of() : Map.copyOf(s),
                r == null ? Map.of() : Map.copyOf(r),
                List.copyOf(c), note == null ? "" : note);
    }
}
