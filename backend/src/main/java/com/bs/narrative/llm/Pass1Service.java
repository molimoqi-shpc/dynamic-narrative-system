package com.bs.narrative.llm;

import com.bs.narrative.dto.ActionRequest.StructuredIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Pass1 编排：玩家文本 → LLM JSON 意图 → StructuredIntent。
 *
 * 防坑规则2 全链路：
 *   1) LLMProvider.chatForJson（DeepSeek JSON Mode 启用）
 *   2) LLMResponseParser 三级降级（纯 JSON / 剥围栏 / 正则提取）
 *   3) 失败 → FallbackProvider.fallbackPass1Json（UNKNOWN 兜底）
 *   4) 任一步异常都绝不抛出，最终必返回 StructuredIntent
 *
 * 每次调用后控制台打印 Token 日志（input/output/total + 成本 + 状态），
 * 同时返回 LLMResponse 供调用方落盘 interaction_log。
 */
@Service
public class Pass1Service {
    @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.service.WorldSettingService worldSettings;

    private static final Logger log = LoggerFactory.getLogger("LLM-TOKEN-LOG");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pass1 调用状态。 */
    public enum Status { SUCCESS, FALLBACK, FAILED }

    private final LLMProvider llmProvider;
    private final LLMConfig cfg;

    public Pass1Service(LLMProvider llmProvider, LLMConfig cfg) {
        this.llmProvider = llmProvider;
        this.cfg = cfg;
    }

    /** Pass1 调用结果：意图 + LLM 元信息（token/cost）+ 状态。 */
    public record Pass1Result(
            StructuredIntent intent,
            LLMResponse llmResponse,
            Status status,
            String note
    ) {}

    /**
     * 解析玩家本回合输入为 StructuredIntent。
     * 即使 LLM 失败、解析失败、API 401，也一定返回非 null StructuredIntent（走 UNKNOWN 兜底）。
     */
    public Pass1Result extractIntent(String playerGodId,
                                     String sceneId,
                                     String playerText,
                                     List<String> presentGodIds,
                                     String sceneSummary) {
        String safeText = playerText == null ? "" : playerText;
        String tag = "scene=" + sceneId + " player=" + playerGodId;
        var control=SimpleWaitCommand.parse(safeText);
        if(control!=null){
            String parsed="{\"waiting\":{\"condition\":\""+control.condition()+"\",\"eventText\":\""+control.eventText()+"\"}}";
            var local=new LLMResponse(parsed,parsed,0,0,0,0);
            log.info("[Pass1] LOCAL_WAIT tag={} condition={} (no model call)",tag,control.condition());
            return new Pass1Result(new StructuredIntent("WAIT",playerGodId,"",null,null,1.0,safeText),local,Status.SUCCESS,"明确等待控制，本地识别，未调用模型");
        }


        // 1) 调用 LLM（JSON Mode）
        LLMResponse llmResp;
        Status status;
        String note;
        try {
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content", Pass1PromptTemplate.systemPrompt()+(worldSettings==null?"":worldSettings.promptForScene(sceneId))),
                    Map.of("role", "user",   "content", Pass1PromptTemplate.userPrompt(
                            playerGodId, safeText, presentGodIds, sceneSummary))
            );
            llmResp = LLMRequestPolicy.INTENT.call(() -> llmProvider.chatForJson(msgs));
            status = Status.SUCCESS;
            note = "LLM 调用成功";
        } catch (LLMApiException e) {
            // LLM 失败 → 走 Fallback
            log.warn("[Pass1] LLM 调用失败 tag={} retriable={} msg={}", tag, e.isRetriable(), e.getMessage());
            llmResp = FallbackProvider.fallbackResponse(
                    FallbackProvider.fallbackPass1Json(safeText),
                    null
            );
            status = Status.FALLBACK;
            note = "LLM 调用失败，触发 Fallback：" + e.getMessage();
            logToken(tag, status, llmResp, safeText, note);
            return new Pass1Result(buildIntentFromJson(llmResp.content(), playerGodId, safeText),
                    llmResp, status, note);
        }

        // 2) 解析 LLM 输出 JSON
        String parsedJson = llmResp.parsedJson();
        if (parsedJson == null) {
            // chatForJson 已通过 LLMResponseParser 提取过；这里二次兜底，避免实现疏漏
            parsedJson = LLMResponseParser.extractJsonOrNull(llmResp.content());
        }
        if (parsedJson == null) {
            // 3) LLM 输出无法解析 → Fallback
            log.warn("[Pass1] LLM 输出 JSON 解析失败 tag={} contentHead={} (前200字符)",
                    tag, truncate(llmResp.content(), 200));
            LLMResponse fbResp = FallbackProvider.fallbackResponse(
                    FallbackProvider.fallbackPass1Json(safeText), null);
            status = Status.FALLBACK;
            note = "LLM 输出 JSON 解析失败，触发 Fallback";
            logToken(tag, status, fbResp, safeText, note);
            return new Pass1Result(buildIntentFromJson(fbResp.content(), playerGodId, safeText),
                    fbResp, status, note);
        }

        // 4) 成功路径
        logToken(tag, status, llmResp, safeText, note);
        return new Pass1Result(buildIntentFromJson(parsedJson, playerGodId, safeText),
                llmResp, status, note);
    }

    /** 把 LLM 输出的 JSON 反序列化成 StructuredIntent；解析失败退化为 UNKNOWN 兜底。 */
    private StructuredIntent buildIntentFromJson(String json, String playerGodId, String playerText) {
        try {
            JsonNode n = MAPPER.readTree(json);
            String intent = n.path("intent").asText("UNKNOWN");
            String actor = n.path("actorGodId").asText(playerGodId);
            String target = n.path("targetGodId").asText("");
            String attackKey = n.has("attackStatKey") && !n.get("attackStatKey").isNull()
                    ? n.get("attackStatKey").asText() : null;
            String itemId = n.has("itemId") && !n.get("itemId").isNull()
                    ? n.get("itemId").asText() : null;
            double conf = n.path("confidence").asDouble(1.0);
            return new StructuredIntent(intent, actor, target, attackKey, itemId, conf, playerText);
        } catch (Exception e) {
            // 最终兜底：JSON 反序列化异常也走 UNKNOWN，绝不抛
            return new StructuredIntent("UNKNOWN", playerGodId, "", null, null, 0.0, playerText);
        }
    }

    /**
     * 控制台 Token 日志（遵循 user_profile：input/output/total + cost）。
     * 格式固定，方便后续 grep / 论文截图。
     */
    private void logToken(String tag, Status status, LLMResponse resp, String playerText, String note) {
        LLMConfig.PricePerMillion p = cfg.getPricePerMillion();
        log.info("""
                ╔══════════════════════════════════════════════════════════════╗
                ║ [LLM-Pass1] Token 消耗报告                                   ║
                ║ ├─ 标识    : {}                                              ║
                ║ ├─ 状态    : {}                                              ║
                ║ ├─ 玩家输入: {}                                              ║
                ║ ├─ 备注    : {}                                              ║
                ║ ├─ Tokens  : input={}, output={}, total={}                   ║
                ║ ├─ 成本    : ¥{} (CNY)                                       ║
                ║ └─ 单价    : 输入¥{}/1M, 输出¥{}/1M                          ║
                ╚══════════════════════════════════════════════════════════════╝""",
                tag,
                status,
                truncate(playerText, 40),
                truncate(note, 60),
                resp.tokenInput(),
                resp.tokenOutput(),
                resp.tokenTotal(),
                String.format("%.6f", resp.costCny()),
                p.getPrompt(),
                p.getCompletion());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
