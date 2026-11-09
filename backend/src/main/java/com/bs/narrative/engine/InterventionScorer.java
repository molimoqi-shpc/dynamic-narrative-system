package com.bs.narrative.engine;

import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.ActualStats;
import com.bs.narrative.engine.model.InterventionScore;
import com.bs.narrative.engine.model.Pass1Intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V2 triangle: ability=0.4*100*A/(A+50), bounded below 40.
 * Will: clamp(80+0.2*(100-trust)) for backstab; otherwise clamp(20+0.6*trust+allianceBonus), weighted 0.3.
 * Gain: clamp(0.5*defender.actualBen+(backstab?20:0)), weighted 0.3.
 * Components and total rounded to one decimal; threshold 30. Existing support choice is preserved.
 */
public final class InterventionScorer {

    public static final double ALLIANCE_BONUS_WILL = 20.0;
    public static final double BACKSTAB_WILL_MULT = 0.8; // 背刺时“信任反而加强背叛意愿”
    public static final double ABILITY_WEIGHT   = 0.4;
    public static final double WILL_WEIGHT      = 0.3;
    public static final double GAIN_WEIGHT      = 0.3;

    private InterventionScorer() {}

    // ------------------- 工具 -------------------
    private static double clamp0100(double v) { return Math.max(0.0, Math.min(100.0, v)); }
    private static double round1(double v)    { return Math.round(v * 10.0) / 10.0; }

    /** 把 relationships 转成 Map<"from->to", GodRelationship>，加速 O(1) 查找。 */
    public static Map<String, GodRelationship> indexRelationships(List<GodRelationship> rels) {
        if (rels == null) return Map.of();
        Map<String, GodRelationship> m = new HashMap<>();
        for (GodRelationship r : rels) {
            if (r == null || r.getSourceId() == null || r.getTargetId() == null) continue;
            m.put(r.getSourceId() + "->" + r.getTargetId(), r);
        }
        return m;
    }

    // ------------------- 主方法 -------------------

    /**
     * 对所有在场的“第三方神明”（!= actor 且 != target）打分并返回所有 total ≥ 阈值的候选。
     *
     * @param p1     Pass1 意图
     * @param actorId  发起方（通常玩家）
     * @param defenderId 防守方（通常在场神明）
     * @param scenePresentGods 场景 presentGods 列表（不含 player）
     * @param actuals godId → ActualStats
     * @param rels    有向关系列表（已至少覆盖 presentGods 两两 + player 在场的对）
     * @return 按 total 降序、≥ INTERVENE_THRESHOLD 的出手方列表；空 List 表示无人出手。
     */
    public static List<InterventionScore> scoreAll(Pass1Intent p1,
                                                   String actorId,
                                                   String defenderId,
                                                   List<String> scenePresentGods,
                                                   Map<String, ActualStats> actuals,
                                                   List<GodRelationship> rels) {
        // 防坑规则 1：actor 或 defender 为空（UNKNOWN/废话场景）→ 无所谓"第三方干预"，直接返回空，
        // 避免把所有在场神明误判为"第三方"打分而触发误出手。
        if (actorId == null || actorId.isBlank() || defenderId == null || defenderId.isBlank()) {
            return List.of();
        }
        if (actuals == null) actuals = Map.of();
        Set<String> presents = new HashSet<>(scenePresentGods == null ? List.of() : scenePresentGods);
        // 第三方：presents 中的 且 不是 actor 也不是 defender 的（防御方=present，actor 可能 player 不在 presents）
        Set<String> thirds = new HashSet<>();
        for (String g : presents) {
            if (g == null) continue;
            if (!g.equals(actorId) && !g.equals(defenderId)) thirds.add(g);
        }
        if (thirds.isEmpty()) return List.of();

        Map<String, GodRelationship> relIndex = indexRelationships(rels);
        IntentEnum intent = p1 == null ? IntentEnum.OTHER : p1.intent();
        StatKey attackKey = (p1 != null && p1.attackStatKey() != null) ? p1.attackStatKey()
                : StatKey.fromPass1Key(intent.firstAttackKey());
        if (attackKey == null) attackKey = StatKey.DOU;

        List<InterventionScore> result = new ArrayList<>();
        for (String t : thirds) {
            ActualStats ts = actuals.get(t);
            if (ts == null) continue;
            // 第三方一般站在 defender：因为 actor=玩家攻击在场神明，符合 Walkthrough；
            // 若第三方是背刺 defender 的（museum→rain 场景），表面站 defender，实际 note 标明算计救援。
            String supports = defenderId;

            // 1) V2 bounded ability; scale by 10 until final decimal rounding.
            double ability = StatsCalculator.normalizedAbility(ts.actualOf(attackKey)) * ABILITY_WEIGHT * 10.0;

            // 2) will：
            // trust(third→defender) 越高越想救；若 isBackstabbed(third→defender)=true，则“假装信任 实则想抢功劳”
            // 公式：base = (100 - trust) × (isBackstabbed ? 0.8 : 1.0) + allianceBonus；
            // 但为了 Walkthrough (trust=30, backstab=1) 得到足够大的 will：改成“背刺直接加值”，
            // 从而 museum_god 该场景 will 足够高。
            GodRelationship third2def = relIndex.get(t + "->" + supports);
            int trust = third2def != null && third2def.getTrustValue() != null ? third2def.getTrustValue() : 50;
            trust = (int) clamp0100(trust);
            boolean allied = third2def != null && Boolean.TRUE.equals(third2def.getAllied());
            boolean backstab = third2def != null && Boolean.TRUE.equals(third2def.getBackstabbed());
            double willRaw = 50.0; // 中性基线 50
            if (backstab) {
                // 背刺：第三方本来“信任 30，但想背刺”，所以会积极出手创造救援机会——极大化意愿
                willRaw = 80.0 + (100 - trust) * 0.2;
            } else {
                // 非背刺：trust 越高越愿意救，同盟再加 20
                willRaw = 20 + trust * 0.6 + (allied ? ALLIANCE_BONUS_WILL : 0);
            }
            willRaw = clamp0100(willRaw);
            double will = willRaw * WILL_WEIGHT * 10.0;

            // 3) gain：利益 = defender.actualBen × 0.5 + (backstab 额外加分 15)
            ActualStats defActuals = actuals.get(supports);
            int defBen = (defActuals != null) ? defActuals.actualBen() : 50;
            double gainRaw = defBen * 0.5 + (backstab ? 20 : 0);
            gainRaw = clamp0100(gainRaw);
            double gain = gainRaw * GAIN_WEIGHT * 10.0;

            // 汇总并保留 1 位小数
            double total = round1((ability + will + gain) / 10.0);
            ability = round1(ability / 10.0);
            will    = round1(will / 10.0);
            gain    = round1(gain / 10.0);

            StringBuilder note = new StringBuilder();
            note.append(t).append(" 能力").append(attackKey.zhLabel).append('=').append(ts.actualOf(attackKey)).append(" → 归一化×0.4=").append(ability);
            note.append("；意愿(trust=").append(trust).append(backstab ? " backstab=1" : "").append(allied ? " allied=1" : "").append(") → ").append(will);
            note.append("；利益(defender.本=").append(defBen).append(" + bonus)=").append(gain);
            note.append("；总分=").append(total);
            if (backstab) note.append(" [表面助 ").append(supports).append(" 实则算计救援]");

            if (total >= StatsCalculator.INTERVENE_THRESHOLD) {
                result.add(new InterventionScore(t, supports, ability, will, gain, total, note.toString()));
            }
        }
        result.sort(Comparator.comparingDouble(InterventionScore::total).reversed());
        return List.copyOf(result);
    }
}
