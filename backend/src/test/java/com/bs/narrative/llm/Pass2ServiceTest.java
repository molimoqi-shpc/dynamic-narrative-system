package com.bs.narrative.llm;

import com.bs.narrative.dto.ActionResponse.NarrativeLine;
import com.bs.narrative.service.StorySummaryValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass2Service 单测。三条路径对称覆盖 Pass1Service：
 *   1) SUCCESS：mock LLM 返合法 lines JSON → 正确反序列化 + 字数检查
 *   2) FALLBACK(LLM 异常)：provider 抛 LLMApiException → 通用旁白 + 每位在场神明补观望
 *   3) FALLBACK(LLM 漏神明)：provider 返缺漏 lines → 自动补观望 line（防情节不完整）
 */
class Pass2ServiceTest {

    private static final Logger log = LoggerFactory.getLogger(Pass2ServiceTest.class);

    private LLMConfig cfg() {
        LLMConfig c = new LLMConfig();
        c.setApiKey("test-key");
        c.setModel("deepseek-chat");
        LLMConfig.PricePerMillion p = new LLMConfig.PricePerMillion();
        p.setPrompt(2.0);
        p.setCompletion(8.0);
        c.setPricePerMillion(p);
        return c;
    }

    // —— 路径1：LLM 返合法 lines JSON，Pass2Service 正确解析 ——
    @Test void success_path_parsesLines() {
        LLMProvider mock = new LLMProvider() {
            @Override public LLMResponse chat(List<Map<String, String>> m) { return null; }
            @Override public LLMResponse chatForJson(List<Map<String, String>> m) {
                // text block 里直接写双引号即可，无需转义；speech/innerThought 字数要 >= 80/40
                String json = """
                        {
                          "lines": [
                            {
                              "godId": "rain_god",
                              "speech": "雨师玄暝凭栏望川，闻玩家之言眉头微蹙，止水符悬于身侧嗡鸣不止，似有示警。缓缓转身，目光凌厉落在那凡人身上，语气不善道：尔等凡胎，怎敢指吾为涝灾元凶？今日来此，恐非偶然。",
                              "innerThought": "玩家将涝灾黑锅扣我头上，恐另有图谋。止水符嗡鸣预示有人窥伺。博物叟虽与我有旧隙，但此刻当防。",
                              "isLying": false
                            },
                            {
                              "godId": "museum_god",
                              "speech": "博物叟自古碑之阴缓步而出，袖中半卷云图隐现，佯作仗义调停道：雨师息怒，此子不过凡人过客，何必与他一般见识。袖底却已凝起背刺之力，伺机而动。",
                              "innerThought": "玩家与雨神冲突，正是出手背刺时机。佯作调停，实则抢夺雨神半卷云图。雨神未知我旧恶，此局可成。",
                              "isLying": true
                            }
                          ]
                        }""";
                return LLMResponse.json(json, json, 800, 350, computeCost(800, 350));
            }
        };
        Pass2Service svc = new Pass2Service(mock, cfg(), new StorySummaryValidator());
        Pass2Service.Pass2Result r = svc.generateNarrative(
                "EXPLOITATION_RESCUE",
                "博物叟背刺雨神，玩家与雨神冲突",
                "雨神，涝灾黑锅我就扣你头上",
                List.of("rain_god", "museum_god"),
                List.of("玩家挑衅雨神", "雨神实际斗=33", "博物叟背刺出手", "博物叟→雨神 信任+10"),
                "丰渡水岸，雨师凭栏");

        assertEquals(Pass2Service.Status.SUCCESS, r.status());
        assertEquals(2, r.narrativeLines().size());
        assertEquals("rain_god", r.narrativeLines().get(0).godId());
        assertEquals("museum_god", r.narrativeLines().get(1).godId());
        // 字数下限规则验证（用户强调：不能太少导致情节不完整）
        assertTrue(r.narrativeLines().get(0).speech().length() >= 80,
                "speech 必须至少 80 字，实际=" + r.narrativeLines().get(0).speech().length());
        assertTrue(r.narrativeLines().get(0).innerThought().length() >= 40,
                "innerThought 必须至少 40 字");
        // 谎徽标
        assertEquals(false, r.lieFlags().get("rain_god"));
        assertEquals(true,  r.lieFlags().get("museum_god"));
        // Token 信息
        assertEquals(800, r.llmResponse().tokenInput());
        assertEquals(350, r.llmResponse().tokenOutput());
        assertEquals(1150, r.llmResponse().tokenTotal());
        // 成本 = 800/1M * 2 + 350/1M * 8 = 0.0016 + 0.0028 = 0.0044
        assertEquals(0.0044, r.llmResponse().costCny(), 0.00001);
        log.info("✓ 路径1 SUCCESS 验证通过：lines={}", r.narrativeLines().size());
    }

    // —— 路径2：LLM 抛异常 → 自动走 Fallback → 通用旁白 + 每位神明补观望 ——
    @Test void llmException_triggersFallback_withObservationLines() {
        LLMProvider mock = new LLMProvider() {
            @Override public LLMResponse chat(List<Map<String, String>> m) { return null; }
            @Override public LLMResponse chatForJson(List<Map<String, String>> m) {
                throw new LLMApiException("DeepSeek timeout", true);
            }
        };
        Pass2Service svc = new Pass2Service(mock, cfg(), new StorySummaryValidator());
        Pass2Service.Pass2Result r = svc.generateNarrative(
                "NO_OP", "玩家输入废话", "今天天气不错",
                List.of("rain_god", "museum_god"),
                null, null);

        assertEquals(Pass2Service.Status.FALLBACK, r.status());
        // 在场每位神明都要有 line（防情节不完整）
        assertEquals(2, r.narrativeLines().size());
        // 第一条是通用旁白（挂在 rain_god 上）
        assertEquals("rain_god", r.narrativeLines().get(0).godId());
        assertTrue(r.narrativeLines().get(0).speech().contains("风穿古碑"));
        // 第二条是博物叟观望补丁
        assertEquals("museum_god", r.narrativeLines().get(1).godId());
        assertTrue(r.narrativeLines().get(1).speech().contains("未发一言"));
        // Fallback 不花 token
        assertEquals(0, r.llmResponse().tokenTotal());
        log.info("✓ 路径2 LLM 异常 Fallback 验证通过：lines={}", r.narrativeLines().size());
    }

    // —— 路径3：LLM 漏掉一位神明 → 自动补观望 line ——
    @Test void llmMissingGod_autoSupplemented() {
        LLMProvider mock = new LLMProvider() {
            @Override public LLMResponse chat(List<Map<String, String>> m) { return null; }
            @Override public LLMResponse chatForJson(List<Map<String, String>> m) {
                // LLM 只输出了 rain_god，漏了 museum_god；speech ≥ 80 字
                String json = """
                        {
                          "lines": [
                            {
                              "godId": "rain_god",
                              "speech": "雨师冷哼一声，止水符嗡鸣不止，目光凌厉落在那凡人身上。凡人，你胆子不小，竟敢在丰渡水岸对本神放肆。今日不教训你一番，怕是要被看轻了。",
                              "innerThought": "玩家挑衅，我当警示，但止水符示警，恐有第三方窥伺，博物叟虽未明动，当提防。",
                              "isLying": false
                            }
                          ]
                        }""";
                return LLMResponse.json(json, json, 500, 200, computeCost(500, 200));
            }
        };
        Pass2Service svc = new Pass2Service(mock, cfg(), new StorySummaryValidator());
        Pass2Service.Pass2Result r = svc.generateNarrative(
                "ATTACKER_WIN", "雨神胜", "受死",
                List.of("rain_god", "museum_god"),
                List.of("雨神胜出"), null);

        // 即使 LLM 漏了博物叟，Pass2Service 也补上观望 line
        assertEquals(Pass2Service.Status.SUCCESS, r.status());
        assertEquals(2, r.narrativeLines().size());
        // 第二条是自动补的博物叟观望
        assertEquals("museum_god", r.narrativeLines().get(1).godId());
        assertTrue(r.narrativeLines().get(1).speech().contains("museum_god"));
        assertFalse(r.lieFlags().get("museum_god"));
        log.info("✓ 路径3 LLM 漏神明自动补观望验证通过：lines={}", r.narrativeLines().size());
    }

    // —— 路径4：LLM 返纯文字 → 解析失败 → Fallback ——
    @Test void llmGarbageOutput_triggersFallback() {
        LLMProvider mock = new LLMProvider() {
            @Override public LLMResponse chat(List<Map<String, String>> m) { return null; }
            @Override public LLMResponse chatForJson(List<Map<String, String>> m) {
                return LLMResponse.raw("啊抱歉这次我没法输出 JSON 格式，下次一定。",
                        100, 50, computeCost(100, 50));
            }
        };
        Pass2Service svc = new Pass2Service(mock, cfg(), new StorySummaryValidator());
        Pass2Service.Pass2Result r = svc.generateNarrative(
                "NO_OP", "", "雨神你好帅",
                List.of("rain_god"), null, null);

        assertEquals(Pass2Service.Status.FALLBACK, r.status());
        assertEquals(1, r.narrativeLines().size());
        log.info("✓ 路径4 LLM 废话输出 Fallback 验证通过");
    }

    private double computeCost(int in, int out) {
        LLMConfig.PricePerMillion p = new LLMConfig.PricePerMillion();
        p.setPrompt(2.0);
        p.setCompletion(8.0);
        return (in / 1_000_000.0) * p.getPrompt() + (out / 1_000_000.0) * p.getCompletion();
    }
    @Test void inventedActorCannotAppearAndFallbackKeepsActualFacts() {
        LLMProvider provider=org.mockito.Mockito.mock(LLMProvider.class);
        String json="{\"lines\":[{\"godId\":\"absent_god\",\"speech\":\"我救了玩家\"}]}";
        org.mockito.Mockito.when(provider.chatForJson(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(LLMResponse.json(json,json,10,10,0));
        var result=new Pass2Service(provider,cfg(),new StorySummaryValidator())
                .generateNarrative("ATTACKER_WIN","攻击方获胜","攻击",List.of("rain_god"),List.of("雨神归减少5"),"水岸");
        assertEquals(Pass2Service.Status.FALLBACK,result.status());
        assertTrue(result.narrativeLines().stream().noneMatch(l -> "absent_god".equals(l.godId())));
        assertTrue(result.narrativeLines().get(0).speech().contains("攻击方获胜"));
        assertFalse(result.narrativeLines().get(0).speech().contains("雨神归减少5"), "公开降级旁白不暴露隐藏数值");
    }
}
