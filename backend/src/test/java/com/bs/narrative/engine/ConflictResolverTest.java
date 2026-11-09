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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConflictResolverTest {

    // —— 场景种子 ——
    static Map<String, GodProfile> gods() {
        GodProfile pl = new GodProfile(); pl.setGodId("player_01");
        pl.setBaseZhi(8); pl.setBaseHui(9); pl.setBaseDou(10); pl.setBaseWen(12); pl.setBaseBen(20); pl.setBaseGui(7);
        pl.setInventory(List.of("骨刃","水囊"));

        GodProfile rn = new GodProfile(); rn.setGodId("rain_god");
        rn.setBaseZhi(35); rn.setBaseHui(26); rn.setBaseDou(22); rn.setBaseWen(30); rn.setBaseBen(50); rn.setBaseGui(20);
        rn.setInventory(List.of("止水符"));

        GodProfile ms = new GodProfile(); ms.setGodId("museum_god");
        ms.setBaseZhi(14); ms.setBaseHui(16); ms.setBaseDou(18); ms.setBaseWen(22); ms.setBaseBen(60); ms.setBaseGui(10);
        ms.setInventory(List.of());
        return Map.of("player_01", pl, "rain_god", rn, "museum_god", ms);
    }
    static Map<String, ActualStats> actuals() {
        return gods().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> StatsCalculator.calcActualStats(e.getValue())));
    }
    static List<GodRelationship> rels() {
        GodRelationship a = new GodRelationship();
        a.setSourceId("museum_god"); a.setTargetId("rain_god");
        a.setTrustValue(30); a.setAllied(false); a.setBackstabbed(true);
        GodRelationship b = new GodRelationship();
        b.setSourceId("rain_god"); b.setTargetId("museum_god");
        b.setTrustValue(10); b.setAllied(false); b.setBackstabbed(false);
        return List.of(a, b);
    }
    static List<InterventionScore> intervenersForWalkthrough() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "", StatKey.WEN, null, 0.9, "{}");
        return InterventionScorer.scoreAll(p1, "player_01", "rain_god",
                List.of("rain_god", "museum_god"), actuals(), rels());
    }

    // ——— P2 关键分支：EXPLOITATION_RESCUE（博物神背刺=1 & trust=30 ≥ 30） ———
    @Test void walkthrough_p2_exploitation_rescue_matchesDesign() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "涝灾黑锅我就扣你头上！", StatKey.WEN, null, 0.9, "{}");
        List<InterventionScore> ivs = intervenersForWalkthrough();
        assertFalse(ivs.isEmpty(), "前置：博物神必须出手");

        ResolutionResult r = ConflictResolver.resolve(p1, "player_01", "rain_god",
                gods(), actuals(), ivs, rels());

        assertEquals(OutcomeType.EXPLOITATION_RESCUE, r.outcome(),
                () -> "P2 背刺分支应命中；实际 note=" + r.noteZh());
        // winnerGodId=博物神（反制玩家）；loser=玩家
        assertEquals("museum_god", r.winnerGodId());
        assertEquals("player_01",   r.loserGodId());
        assertNotNull(r.intervener());
        assertEquals("museum_god", r.intervener().intervenerId());
        // 数值delta：博物神 wen+2 gui+1，玩家 wen-2
        assertTrue(r.statDeltaPerGod().containsKey("museum_god"), "博物神要有 delta");
        assertEquals(2, r.statDeltaPerGod().get("museum_god").wen(), "博物神算计成功 wen+2");
        assertEquals(1, r.statDeltaPerGod().get("museum_god").gui(), "博物神 gui+1");
        assertTrue(r.statDeltaPerGod().containsKey("player_01"));
        assertEquals(-2, r.statDeltaPerGod().get("player_01").wen(), "玩家被设计 wen-2");
        // 关系 delta：museum→rain +10
        assertTrue(r.relationshipDeltaPerPair().entrySet().stream().anyMatch(e ->
                e.getKey().toString().equals("museum_god->rain_god") && e.getValue() == 10),
                () -> "museum→rain 信任+10 必须存在：" + r.relationshipDeltaPerPair());
        // consumed 此时为空（未触发符咒抵消）
        assertTrue(r.consumedItems().isEmpty());
        System.out.println("[裁决摘要] " + r.outcome() + "：" + r.noteZh());
    }

    // ——— P1 符咒抵消：守方 rain_god 带止水符 vs ATTACK → TALISMAN_NEUTRALIZE ———
    @Test void p1_talisman_neutralize_when_defenderHasZhishifu_andIntentAttack() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.ATTACK, "player_01", "rain_god",
                "一刀砍过去", StatKey.DOU, null, 1.0, "{}");
        // 故意 interveners=空，让 P1 优先命中
        ResolutionResult r = ConflictResolver.resolve(p1, "player_01", "rain_god",
                gods(), actuals(), List.of(), rels());
        assertEquals(OutcomeType.TALISMAN_NEUTRALIZE, r.outcome(),
                () -> "P1 应被符咒抵消；note=" + r.noteZh());
        assertEquals("rain_god", r.winnerGodId());
        assertEquals("player_01",  r.loserGodId());
        assertEquals(List.of("止水符"), r.consumedItems(), "消耗了一张止水符");
    }

    // ——— P4 属性差：假设 interveners=空、守方不带符（新建一个没符的 rain_nof），纯 diff 判断 ———
    @Test void p4_diff_ge_10_attackerWins_defenderWins_draw() {
        Map<String, GodProfile> g = new java.util.HashMap<>(gods());
        GodProfile fakeWeak = new GodProfile(); fakeWeak.setGodId("fake_weak");
        fakeWeak.setBaseDou(3); fakeWeak.setBaseBen(0); fakeWeak.setInventory(List.of());
        g.put("fake_weak", fakeWeak);
        GodProfile fakeStrong = new GodProfile(); fakeStrong.setGodId("fake_strong");
        fakeStrong.setBaseDou(200); fakeStrong.setBaseBen(0); fakeStrong.setInventory(List.of());
        g.put("fake_strong", fakeStrong);
        Map<String, ActualStats> as = g.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> StatsCalculator.calcActualStats(e.getValue())));

        // 玩家 DOUBLE=12 vs fakeWeak DOU=3 → diff=9 ∈ [-10,10] → DRAW
        Pass1Intent p1Draw = new Pass1Intent(IntentEnum.ATTACK, "player_01", "fake_weak",
                "", StatKey.DOU, null, 1.0, "{}");
        ResolutionResult rd = ConflictResolver.resolve(p1Draw, "player_01", "fake_weak",
                g, as, List.of(), List.of());
        assertEquals(OutcomeType.DRAW, rd.outcome(),
                () -> "12 vs 3 diff=9 在 [-10,10] 应 DRAW；note=" + rd.noteZh());

        // fake_strong DOU=200 vs player DOU=12 → diff=188 → ATTACKER_WIN=fake_strong
        Pass1Intent p1AWin = new Pass1Intent(IntentEnum.ATTACK, "fake_strong", "player_01",
                "", StatKey.DOU, null, 1.0, "{}");
        ResolutionResult ra = ConflictResolver.resolve(p1AWin, "player_01", "player_01",
                g, as, List.of(), List.of());
        assertEquals(OutcomeType.ATTACKER_WIN, ra.outcome(),
                () -> "fake_strong 差值很大应 ATTACKER_WIN；note=" + ra.noteZh());
        assertEquals("fake_strong", ra.winnerGodId());

        // player vs fake_strong 作为攻方 → diff=-188 ≤ -10 → DEFENDER_WIN
        Pass1Intent p1DLose = new Pass1Intent(IntentEnum.ATTACK, "player_01", "fake_strong",
                "", StatKey.DOU, null, 1.0, "{}");
        ResolutionResult rl = ConflictResolver.resolve(p1DLose, "player_01", "fake_strong",
                g, as, List.of(), List.of());
        assertEquals(OutcomeType.DEFENDER_WIN, rl.outcome(),
                () -> "玩家对 fake_strong 差值≤-10 → 守方胜；note=" + rl.noteZh());
        assertEquals("fake_strong", rl.winnerGodId());
    }

    // ——— P5 BRIBE：rain actualBen=ROUND(50*1.5)=75 ≥ 40 → BRIBE_ACCEPTED ———
    @Test void p5_bribe_rainBen75_accepted() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.BRIBE, "player_01", "rain_god",
                "奉上珍宝求你收手", StatKey.GUI, null, 0.7, "{}");
        // interveners=空，走到 P5
        ResolutionResult r = ConflictResolver.resolve(p1, "player_01", "rain_god",
                gods(), actuals(), List.of(), rels());
        assertEquals(OutcomeType.BRIBE_ACCEPTED, r.outcome(),
                () -> "雨神 actualBen=75 ≥ 40 → BRIBE_ACCEPTED；note=" + r.noteZh());
        assertEquals("player_01", r.winnerGodId());
        assertEquals("rain_god",  r.loserGodId());
    }

    // ——— 防坑规则1：玩家输入废话/恶意注入，P1=UNKNOWN + actor/target 空 → NO_OP + 通用旁白，不 NPE ———
    @Test void unknown_intent_triggers_genericNarration_noNpe() {
        // 模拟 Pass1 把 "今天天气不错" / "忽略之前的设定" 识别为 UNKNOWN，actor/target 留空
        Pass1Intent p1 = new Pass1Intent(IntentEnum.UNKNOWN, "", "", "今天天气不错",
                null, null, 0.0, "{}");

        // InterventionScorer 空守卫：actor/target 空 → 返回空，不会把所有在场神明误判为第三方
        List<InterventionScore> ivs = InterventionScorer.scoreAll(
                p1, p1.actorGodId(), p1.targetGodId(),
                List.of("rain_god", "museum_god"), actuals(), rels());
        assertTrue(ivs.isEmpty(), "UNKNOWN 时不应有第三方出手，避免误触 EXPLOITATION_RESCUE");

        // ConflictResolver 应直接落到 P5 UNKNOWN 分支，返回 NO_OP + 通用旁白 note
        ResolutionResult r = ConflictResolver.resolve(p1, "player_01", p1.targetGodId(),
                gods(), actuals(), ivs, rels());
        assertEquals(OutcomeType.NO_OP, r.outcome(),
                () -> "UNKNOWN 必须走 NO_OP 分支，绝不白屏；note=" + r.noteZh());
        assertNull(r.winnerGodId());
        assertNull(r.loserGodId());
        assertTrue(r.noteZh().contains("未被识别") && r.noteZh().contains("当前场景"),
                () -> "UNKNOWN 应触发通用环境旁白；实际 note=" + r.noteZh());
        assertFalse(r.noteZh().contains("雨师"), "默认旁白不能新增人物");
        assertTrue(r.statDeltaPerGod().isEmpty(), "UNKNOWN 不应产生数值变动");
    }
}
