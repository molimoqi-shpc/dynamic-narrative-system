package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.dto.ActionRequest.StructuredIntent;
import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.OutcomeType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleEngineFacadeService 单测（覆盖 FactsChineseBuilder / LieDetector / DeltaApplier）。
 *
 * 用 Walkthrough 完整场景验证：
 *   1) EXPLOITATION_RESCUE 路径：玩家挑衅雨神 → 博物叟背刺出手
 *   2) UNKNOWN 路径：玩家输入废话 → NO_OP + 通用旁白
 */
class RuleEngineFacadeServiceTest {

    private final RuleEngineFacadeService service = new RuleEngineFacadeService();

    private GodProfile rain() {
        GodProfile p = new GodProfile();
        p.setGodId("rain_god");
        p.setGodName("雨师玄暝");
        p.setBaseZhi(30); p.setBaseHui(28); p.setBaseDou(22);
        p.setBaseWen(20); p.setBaseBen(50); p.setBaseGui(80);
        p.setInventory(List.of("止水符"));
        return p;
    }

    private GodProfile museum() {
        GodProfile p = new GodProfile();
        p.setGodId("museum_god");
        p.setGodName("博物叟");
        p.setBaseZhi(55); p.setBaseHui(50); p.setBaseDou(18);
        p.setBaseWen(45); p.setBaseBen(60); p.setBaseGui(30);
        p.setInventory(List.of("博古录", "窥心镜"));
        return p;
    }

    private GodProfile player() {
        GodProfile p = new GodProfile();
        p.setGodId("player_01");
        p.setGodName("玩家");
        p.setBaseZhi(10); p.setBaseHui(12); p.setBaseDou(10);
        p.setBaseWen(11); p.setBaseBen(20); p.setBaseGui(15);
        p.setInventory(List.of("骨刃", "水囊"));
        return p;
    }

    private GameScene scene() {
        GameScene s = new GameScene();
        s.setSceneId("scene_fengdu");
        s.setSessionId("sess_1");
        s.setLocationName("丰渡水岸");
        s.setPresentGods(List.of("rain_god", "museum_god"));
        s.setSceneSummary("丰渡水岸，雨师凭栏。");
        return s;
    }

    private List<GodRelationship> rels() {
        return List.of(
                rel("rain_god", "museum_god", 15, false, false,
                        "雨神视博物叟为寻常长者。"),
                // 博物叟→雨神：backstab=true, trust=30（恰好等于 EXPLOIT_TRUST_MIN）
                rel("museum_god", "rain_god", 30, false, true,
                        "博物叟暗中视雨神为棋子，曾背刺之。"),
                rel("player_01", "rain_god", 35, false, false,
                        "玩家初见雨神。"),
                rel("player_01", "museum_god", 60, false, false,
                        "玩家视博物叟为长者。")
        );
    }

    private GodRelationship rel(String from, String to, int trust,
                                boolean allied, boolean backstabbed, String note) {
        GodRelationship r = new GodRelationship();
        r.setSessionId("sess_1");
        r.setSourceId(from);
        r.setTargetId(to);
        r.setTrustValue(trust);
        r.setAllied(allied);
        r.setBackstabbed(backstabbed);
        r.setNote(note);
        return r;
    }

    @Test
    void walkthrough_exploitationRescue() {
        Map<String, GodProfile> gods = new LinkedHashMap<>();
        gods.put("rain_god", rain());
        gods.put("museum_god", museum());
        gods.put("player_01", player());

        StructuredIntent si = new StructuredIntent(
                IntentEnum.PROVOKE.name(), "player_01", "rain_god",
                "dou", null, 0.95, null);
        ActionRequest req = new ActionRequest("player_01", "scene_fengdu",
                "雨神，丰都涝灾这笔账我就赖你头上！", si);

        ActionResponse resp = service.resolveWithSeeds(req, scene(), gods, rels());

        assertEquals(OutcomeType.EXPLOITATION_RESCUE.name(), resp.outcomeType());
        assertNotNull(resp.factsChinese());
        assertFalse(resp.factsChinese().isEmpty());
        assertTrue(resp.factsChinese().stream().anyMatch(f -> f.contains("实际六维：知=")
                && f.contains(",闻=") && f.contains(",归=")));
        assertTrue(resp.factsChinese().stream().noneMatch(f -> f.contains("智=")
                || f.contains("文=") || f.contains("诡=")));
        // lieFlags 应该有博物叟的谎徽标
        assertTrue(resp.lieFlags().getOrDefault("museum_god", false),
                "博物叟背刺出手应标记为谎");
    }

    @Test
    void unknownIntent_noOpWithNarration() {
        Map<String, GodProfile> gods = new LinkedHashMap<>();
        gods.put("rain_god", rain());
        gods.put("museum_god", museum());
        gods.put("player_01", player());

        StructuredIntent si = new StructuredIntent(
                IntentEnum.UNKNOWN.name(), null, null,
                null, null, 0.5, null);
        ActionRequest req = new ActionRequest("player_01", "scene_fengdu",
                "今天天气不错", si);

        ActionResponse resp = service.resolveWithSeeds(req, scene(), gods, rels());

        assertEquals(OutcomeType.NO_OP.name(), resp.outcomeType());
        assertNotNull(resp.factsChinese());
    }
}
