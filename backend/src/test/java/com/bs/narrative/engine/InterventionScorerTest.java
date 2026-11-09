package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.ActualStats;
import com.bs.narrative.engine.model.InterventionScore;
import com.bs.narrative.engine.model.Pass1Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 丰都 Walkthrough 三元组：博物神(ability/will/gain) 总分 ≥ 30 必须出手。 */
class InterventionScorerTest {

    static Map<String, GodProfile> seedGods() {
        GodProfile pl = new GodProfile(); pl.setGodId("player_01");
        pl.setBaseZhi(8); pl.setBaseHui(9); pl.setBaseDou(10); pl.setBaseWen(12); pl.setBaseBen(20); pl.setBaseGui(7);

        GodProfile rn = new GodProfile(); rn.setGodId("rain_god");
        rn.setBaseZhi(35); rn.setBaseHui(26); rn.setBaseDou(22); rn.setBaseWen(30); rn.setBaseBen(50); rn.setBaseGui(20);

        GodProfile ms = new GodProfile(); ms.setGodId("museum_god");
        ms.setBaseZhi(14); ms.setBaseHui(16); ms.setBaseDou(18); ms.setBaseWen(22); ms.setBaseBen(60); ms.setBaseGui(10);
        return Map.of("player_01", pl, "rain_god", rn, "museum_god", ms);
    }

    static Map<String, ActualStats> actuals() {
        return seedGods().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> StatsCalculator.calcActualStats(e.getValue())));
    }

    /** C2-C3 对应关系：museum→rain trust=30 backstab=1；rain→museum trust=10 backstab=0 */
    static List<GodRelationship> rels() {
        GodRelationship a = new GodRelationship();
        a.setSourceId("museum_god"); a.setTargetId("rain_god");
        a.setTrustValue(30); a.setAllied(false); a.setBackstabbed(true);

        GodRelationship b = new GodRelationship();
        b.setSourceId("rain_god"); b.setTargetId("museum_god");
        b.setTrustValue(10); b.setAllied(false); b.setBackstabbed(false);
        return List.of(a, b);
    }

    @Test void walkthrough_museumMustIntervene_score_GE_30() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "就扣涝灾黑锅！", StatKey.WEN, null, 0.9, "{}");
        List<String> present = List.of("rain_god", "museum_god");
        List<InterventionScore> out = InterventionScorer.scoreAll(
                p1, "player_01", "rain_god", present, actuals(), rels());

        assertFalse(out.isEmpty(), "博物神必须命中 ≥ 30 阈值而出手");
        InterventionScore top = out.get(0);
        assertEquals("museum_god", top.intervenerId());
        assertEquals("rain_god", top.supportsGodId());
        assertTrue(top.total() >= StatsCalculator.INTERVENE_THRESHOLD,
                () -> "博物神总分=" + top.total() + " 应≥阈值=" + StatsCalculator.INTERVENE_THRESHOLD
                      + "；明细：" + top.shortSummary());
        System.out.println("[干预得分] " + top.shortSummary());
        System.out.println("          中文说明：" + top.noteZh());
    }

    @Test void thirdParty_absent_if_noOtherPresentGods() {
        // 场景只有 rain_god（无第三方） → 空 List
        Pass1Intent p1 = Pass1Intent.ofSimple(IntentEnum.ATTACK, "player_01", "rain_god");
        List<String> onlyRain = List.of("rain_god");
        List<InterventionScore> out = InterventionScorer.scoreAll(
                p1, "player_01", "rain_god", onlyRain, actuals(), rels());
        assertTrue(out.isEmpty());
    }

    @Test void museumAbilityUsesBoundedV2Normalization() {
        // PROVOKE uses WEN and the bounded V2 ability normalization.
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "", StatKey.WEN, null, 0.9, "{}");
        List<String> present = List.of("rain_god", "museum_god");
        List<InterventionScore> out = InterventionScorer.scoreAll(
                p1, "player_01", "rain_god", present, actuals(), rels());
        assertFalse(out.isEmpty());
        InterventionScore top = out.get(0);
        // Preserve one decimal and the component budget.
        assertEquals(Math.round(StatsCalculator.normalizedAbility(actuals().get("museum_god").actualWen())*0.4*10)/10.0, top.ability(), 0.01,
                () -> "V2 干预能力不超过 40，实际=" + top.ability());
    }
}
