package com.bs.narrative.web;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.dto.EngineDemoResponse;
import com.bs.narrative.llm.LLMResponse;
import com.bs.narrative.llm.Pass1Service;
import com.bs.narrative.llm.Pass2Service;
import com.bs.narrative.service.RuleEngineFacadeService;
import com.bs.narrative.service.FengduDemoStory;
import com.bs.narrative.service.EngineDemoAnalysis;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录后可运行的独立旧版规则示例。演算使用内存种子，不写游戏存档、不调用模型。
 *   - POST /api/engine/demo/fengdu-walkthrough
 *     body: {}。账号身份由正常鉴权确认，演示角色与行动始终由服务端固定构造。
 *     → 返回完整裁决、逐幕对白、逐句谎言说明与可展开的规则快照。
 *
 *   - GET  /api/engine/health → {"ok": true, "service": "rules-engine-core", "version": "0.1.0"}
 *
 * pass1-test/pass2-test 是另行保留的模型调试端点；本页面的固定示例不会调用它们。
 */
@RestController
@RequestMapping("/api/engine")
@CrossOrigin
public class EngineDemoController {

    private final RuleEngineFacadeService ruleEngine;
    private final Pass1Service pass1Service;
    private final Pass2Service pass2Service;

    public EngineDemoController(RuleEngineFacadeService ruleEngine,
                                Pass1Service pass1Service,
                                Pass2Service pass2Service) {
        this.ruleEngine = ruleEngine;
        this.pass1Service = pass1Service;
        this.pass2Service = pass2Service;
    }

    // ====== Health ======
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("service", "rules-engine-core");
        m.put("version", "0.1.0");
        m.put("timestamp", System.currentTimeMillis());
        return m;
    }

    // ====== 丰都 Walkthrough（硬编码种子，零依赖数据库 & LLM） ======
    @PostMapping("/demo/fengdu-walkthrough")
    public ResponseEntity<EngineDemoResponse> fengduWalkthrough() {
        // 1) Scene
        GameScene scene = new GameScene();
        scene.setSceneId("scene_fengdu");
        scene.setLocationName("丰都");
        scene.setPresentGods(List.of("rain_god", "museum_god"));
        scene.setSceneSummary("丰都城雨霪数月，百姓流离，玩家决意栽赃雨神以开启博弈。");

        // 2) 4 神明基础档案（与 init-all.sql 种子一致，保证 StatsCalculator 公式出同样结果）
        GodProfile pl = god("player_01",  "沈砚",  8,  9, 10, 12, 20,  7, List.of("骨刃", "水囊"));
        GodProfile rn = god("rain_god",   "雨神", 35, 26, 22, 30, 50, 20, List.of("止水符"));
        GodProfile ms = god("museum_god", "博物神",14, 16, 18, 22, 60, 10, List.of());
        GodProfile fm = god("farm_god",   "农神", 10,  8,  3,  6, 50,  2, List.of());
        Map<String, GodProfile> gods = Map.of("player_01", pl, "rain_god", rn, "museum_god", ms, "farm_god", fm);

        // 3) 关系网（trust=30,backstab=1 博物神→雨神）
        GodRelationship r1 = new GodRelationship();
        r1.setSourceId("museum_god"); r1.setTargetId("rain_god");
        r1.setTrustValue(30); r1.setAllied(false); r1.setBackstabbed(true);
        r1.setNote("博物神对雨神的背刺历史");
        GodRelationship r2 = new GodRelationship();
        r2.setSourceId("rain_god"); r2.setTargetId("museum_god");
        r2.setTrustValue(10); r2.setAllied(false); r2.setBackstabbed(false);
        List<GodRelationship> rels = List.of(r1, r2);
        // Capture directed starting relations before the rule engine applies in-memory deltas.
        List<Map<String,Object>> initialRelationships = rels.stream().map(r -> Map.<String,Object>of(
                "sourceId", r.getSourceId(), "targetId", r.getTargetId(),
                "trust", r.getTrustValue(), "backstabbed", Boolean.TRUE.equals(r.getBackstabbed()))).toList();

        // Authenticated account IDs belong to access control, never to the isolated fixture cast.
        // Always rebuild this fixed example; no real save, actor or client-supplied intent is used.
        ActionRequest req = new ActionRequest(
                    "player_01", "scene_fengdu", "雨神，丰都涝灾这笔账我就赖你头上！",
                    new ActionRequest.StructuredIntent(
                            "PROVOKE", "player_01", "rain_god",
                            "WEN", null, 0.9,
                            "涝灾黑锅我就扣你头上！"
                    )
            );

        // Capture worked values from the initial cast, before the sample applies its own deltas.
        var analysis = EngineDemoAnalysis.describe(List.of(pl,rn,ms));
        ActionResponse resp = ruleEngine.resolveWithSeeds(req, scene, gods, rels);
        return ResponseEntity.ok(FengduDemoStory.illustrate(resp, initialRelationships, analysis));
    }

    // ====== 调试：把所有 6 个 engine 纯函数 + 关键常量以 JSON 汇总返回 ======
    @GetMapping("/demo/formula-constants")
    public Map<String, Object> formulaConstants() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actual_stat_formula", "V2: round(C(基础) × 根基加成 × 有限协同)；本与归不乘根基加成");
        m.put("formula_book",com.bs.narrative.engine.StatFormulaBook.describe(null));
        m.put("ben_max", 100);
        m.put("intervene_threshold", 30.0);
        m.put("intervention_triangle_weights", Map.of("ability", 0.4, "will", 0.3, "gain", 0.3));
        m.put("diff_clear_win", 10);
        m.put("diff_clear_win_formula", "max(10,round(0.10×(攻方实际值+守方实际值)))");
        m.put("exploit_rescue_trust_min", 30);
        m.put("bribe_accept_actualBen_min", 40);
        m.put("resolution_priority_order", List.of(
                "P2 BACKSTAB exploitation_rescue (HIGHEST - 高阶主体算计)",
                "P1 TALISMAN_NEUTRALIZE（符咒抵消）",
                "P3 THIRD_PARTY_INTERVENE（普通第三方支持）",
                "P4 ATTACKER_WIN / DEFENDER_WIN / DRAW（属性差对抗）",
                "P5 BRIBE_ACCEPTED / NO_OP / 意图特有分支"
        ));
        m.put("talisman_keywords", List.of("符", "咒", "印", "阵", "幡", "令"));
        return m;
    }

    private static GodProfile god(String id, String name,
                                  int z, int h, int d, int w, int b, int g,
                                  List<String> inv) {
        GodProfile p = new GodProfile();
        p.setGodId(id);   p.setGodName(name);
        p.setGodStatus("ALIVE"); p.setGodEmotion("CALM");
        p.setBaseZhi(z);  p.setBaseHui(h);  p.setBaseDou(d);
        p.setBaseWen(w);  p.setBaseBen(b);  p.setBaseGui(g);
        p.setInventory(inv);
        return p;
    }

    // ====== Pass1 演示：直接调 Pass1Service，验证 LLM 链路 + Token 日志 ======
    //   无 API key 时会自动走 Fallback，Token 日志照常打印（0 token + 0 cost）
    @PostMapping("/demo/pass1-test")
    public Map<String, Object> pass1Test(@RequestBody Map<String, String> body) {
        String playerText = body.getOrDefault("playerText", "今天天气不错");
        String playerId   = body.getOrDefault("playerId", "player_01");
        String sceneId    = body.getOrDefault("sceneId", "scene_fengdu");

        // 调用 Pass1Service（无 API key → 自动走 Fallback → UNKNOWN + 通用旁白）
        Pass1Service.Pass1Result r = pass1Service.extractIntent(
                playerId, sceneId, playerText,
                List.of("rain_god", "museum_god"),
                "丰渡水岸，雨师玄暝凭栏望川，博物叟隐于古碑之阴。");

        // 返回结果，Token 信息也会同步打印到后端控制台（LLM-TOKEN-LOG logger）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",         r.status().name());
        m.put("note",           r.note());
        m.put("structuredIntent", r.intent());
        Map<String, Object> llm = new LinkedHashMap<>();
        LLMResponse resp = r.llmResponse();
        llm.put("tokenInput",  resp.tokenInput());
        llm.put("tokenOutput", resp.tokenOutput());
        llm.put("tokenTotal",  resp.tokenTotal());
        llm.put("costCny",     BigDecimal.valueOf(resp.costCny())
                                  .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString());
        m.put("llmResponse",   llm);
        return m;
    }

    // ====== Pass2 演示：直接调 Pass2Service，验证叙事生成 + 字数下限 + Token 日志 ======
    //   无 API key 时自动走 Fallback，通用环境旁白 + 在场神明补观望 line
    @PostMapping("/demo/pass2-test")
    public Map<String, Object> pass2Test(@RequestBody Map<String, String> body) {
        String playerText = body.getOrDefault("playerText", "雨神，丰都涝灾这笔账我就赖你头上！");
        String outcomeType = body.getOrDefault("outcomeType", "EXPLOITATION_RESCUE");
        String outcomeNote = body.getOrDefault("outcomeNote", "博物叟背刺雨神，玩家与雨神冲突");

        Pass2Service.Pass2Result r = pass2Service.generateNarrative(
                outcomeType, outcomeNote, playerText,
                List.of("rain_god", "museum_god"),
                List.of("玩家挑衅雨神", "示例仅演示叙事结构，实际六维以 V2 规则结算为准", "博物叟背刺出手"),
                "丰渡水岸，雨师玄暝凭栏望川，博物叟隐于古碑之阴。");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("note",   r.note());
        m.put("narrativeLines", r.narrativeLines());
        m.put("lieFlags",       r.lieFlags());
        Map<String, Object> llm = new LinkedHashMap<>();
        LLMResponse resp = r.llmResponse();
        llm.put("tokenInput",  resp.tokenInput());
        llm.put("tokenOutput", resp.tokenOutput());
        llm.put("tokenTotal",  resp.tokenTotal());
        llm.put("costCny",     BigDecimal.valueOf(resp.costCny())
                                  .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString());
        m.put("llmResponse",   llm);
        return m;
    }
}
