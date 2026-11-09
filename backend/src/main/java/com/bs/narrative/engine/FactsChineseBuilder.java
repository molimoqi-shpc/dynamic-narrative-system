package com.bs.narrative.engine;

import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.OutcomeType;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.ActualStats;
import com.bs.narrative.engine.model.InterventionScore;
import com.bs.narrative.engine.model.Pass1Intent;
import com.bs.narrative.engine.model.ResolutionResult;
import com.bs.narrative.engine.model.StatDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 构造 Pass2 注入用的「事实中文清单」——纯函数、可复现、与规则裁决结果严格一致。
 * 每条事实都是一句独立中文，Pass2 LLM 必须逐条遵守（输出若有矛盾被 LiesDetector 标谎徽）。
 */
public final class FactsChineseBuilder {

    private FactsChineseBuilder() {}

    public static List<String> build(Pass1Intent p1,
                                     Map<String, ActualStats> beforeActuals,
                                     ResolutionResult resolution,
                                     List<InterventionScore> candidates,
                                     Map<String, String> godNames /* 可选：godId→中文名映射 */) {
        List<String> facts = new ArrayList<>();
        if (p1 == null || resolution == null) return facts;

        IntentEnum in = p1.intent();
        String aId = p1.actorGodId();
        String tId = p1.targetGodId();
        String aName = nameOf(godNames, aId);
        String tName = nameOf(godNames, tId);
        StatKey ak = p1.attackStatKey() != null ? p1.attackStatKey()
                : StatKey.fromPass1Key(in.firstAttackKey());
        if (ak == null) ak = StatKey.DOU;

        // F1 行动概要
        facts.add(String.format("行动方[%s]以意图【%s:%s】对目标方[%s]发起互动，玩家原句摘要=\"%s\"。",
                aName, in.name(), in.zhLabel, tName, emptySafe(p1.argument())));
        // F2 对抗属性
        if (in == IntentEnum.ATTACK || in == IntentEnum.PROVOKE || in == IntentEnum.THREAT
                || in == IntentEnum.DEFEND || in == IntentEnum.BRIBE || in == IntentEnum.CAST_TALISMAN) {
            ActualStats aAc = beforeActuals == null ? null : beforeActuals.get(aId);
            ActualStats tAc = beforeActuals == null ? null : beforeActuals.get(tId);
            if (aAc != null && tAc != null) {
                int diff = StatsCalculator.statDifference(aAc, tAc, ak);
                facts.add(String.format("对抗属性为【%s(%s)】：%s 实际值=%d 对 %s 实际值=%d，差值=%d。",
                        ak.zhLabel, ak.name(), aName, aAc.actualOf(ak),
                        tName, tAc.actualOf(ak), diff));
            }
        }
        // F3 六维表（用户设计：知、慧、斗、闻、本、归）
        facts.add("六维计算版本："+StatsCalculator.FORMULA_VERSION+"。本为神职感悟度，归为忠诚意志；实际值含成长曲线与有限协同，不代表财富或诡计。");
        if (beforeActuals != null) {
            for (Map.Entry<String, ActualStats> e : beforeActuals.entrySet()) {
                ActualStats s = e.getValue();
                String nm = nameOf(godNames, e.getKey());
                facts.add(String.format("%s 实际六维：知=%d,慧=%d,斗=%d,闻=%d,本=%d,归=%d。",
                        nm, s.actualZhi(), s.actualHui(), s.actualDou(),
                        s.actualWen(), s.actualBen(), s.actualGui()));
            }
        }
        // F4 使用物品/符
        if (!resolution.consumedItems().isEmpty()) {
            facts.add(String.format("本回合消耗物品：[%s]。", String.join("、", resolution.consumedItems())));
        }
        // F5 所有第三方评估
        if (candidates != null && !candidates.isEmpty()) {
            for (InterventionScore c : candidates) {
                facts.add(String.format("第三方神明[%s]三角评分（能力=%.1f / 意愿=%.1f / 利益=%.1f / 总分=%.1f）→ 支持[%s]。%s",
                        nameOf(godNames, c.intervenerId()), c.ability(), c.will(), c.gain(), c.total(),
                        nameOf(godNames, c.supportsGodId()), c.noteZh() == null ? "" : c.noteZh()));
            }
        }
        // F6 最终裁决
        OutcomeType oc = resolution.outcome();
        facts.add(String.format("规则引擎最终裁决类型：%s(%s)。摘要：%s", oc.name(), oc.zhLabel,
                emptySafe(resolution.noteZh())));
        if (resolution.intervener() != null) {
            facts.add(String.format("真正出手的第三方：[%s]，支持[%s]，总分=%.1f ≥ %.1f 阈值。",
                    nameOf(godNames, resolution.intervener().intervenerId()),
                    nameOf(godNames, resolution.intervener().supportsGodId()),
                    resolution.intervener().total(),
                    StatsCalculator.INTERVENE_THRESHOLD));
        }
        if (resolution.winnerGodId() != null) {
            facts.add(String.format("胜方：[%s]；败方：[%s]。",
                    nameOf(godNames, resolution.winnerGodId()),
                    resolution.loserGodId() != null ? nameOf(godNames, resolution.loserGodId()) : "无"));
        }
        // F7 数值变动（施加前→施加后 diff）
        if (resolution.statDeltaPerGod() != null) {
            for (Map.Entry<String, StatDelta> e : resolution.statDeltaPerGod().entrySet()) {
                StatDelta d = e.getValue();
                if (d == null || d.isZero()) continue;
                String nm = nameOf(godNames, e.getKey());
                List<String> parts = new ArrayList<>();
                for (StatKey k : StatKey.values()) {
                    int v = d.of(k);
                    if (v != 0) parts.add(String.format("%s%s%d", k.zhLabel, (v > 0 ? "+" : ""), v));
                }
                if (!parts.isEmpty()) facts.add(String.format("%s 的基础六维变更为：%s。", nm, String.join("，", parts)));
            }
        }
        if (resolution.relationshipDeltaPerPair() != null) {
            resolution.relationshipDeltaPerPair().forEach((pair, delta) -> {
                if (delta == null || delta == 0) return;
                facts.add(String.format("有向关系 [%s→%s] 的 trust_value 变动 %+d。",
                        nameOf(godNames, pair.from), nameOf(godNames, pair.to), delta));
            });
        }

        // F8 强约束提醒：禁止 Pass2 引入事实清单未列出的新设定
        facts.add("【强约束】生成的对白与内心活动必须与以上事实在逻辑上完全一致，不得引入未列的新设定、新物品或新神明。");
        return List.copyOf(facts);
    }

    private static String emptySafe(String s) { return (s == null) ? "" : s; }

    private static String nameOf(Map<String, String> map, String id) {
        if (id == null) return "null";
        if (map != null) {
            String v = map.get(id);
            if (v != null && !v.isBlank()) return v;
        }
        return id;
    }
}
