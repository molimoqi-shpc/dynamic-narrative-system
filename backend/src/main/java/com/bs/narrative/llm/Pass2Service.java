package com.bs.narrative.llm;

import com.bs.narrative.dto.ActionResponse.NarrativeLine;
import com.bs.narrative.service.StorySummaryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass2 编排：规则结算结果 + 事实清单 → 古风叙事对白。
 *
 * 防坑规则2 全链路（与 Pass1Service 对称）：
 *   1) LLMProvider.chatForJson
 *   2) LLMResponseParser 三级降级
 *   3) 失败 → FallbackProvider.fallbackPass2Json（通用环境旁白）
 *   4) 绝不抛异常，最终必返回 List<NarrativeLine> + lieFlags
 *
 * Token 日志与 Pass1Service 同格式，便于双 Pass 汇总。
 *
 * hidden_summary 校验：委托 StorySummaryValidator 在 stage 边界完成 schema 校验，
 * 失败时走 Fallback 而非抛异常（保证玩家体验不中断）。
 */
@Service
public class Pass2Service {

    private static final Logger log = LoggerFactory.getLogger("LLM-TOKEN-LOG");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMProvider llmProvider;
    private final LLMConfig cfg;
    private final StorySummaryValidator summaryValidator;

    public Pass2Service(LLMProvider llmProvider, LLMConfig cfg, StorySummaryValidator summaryValidator) {
        this.llmProvider = llmProvider;
        this.cfg = cfg;
        this.summaryValidator = summaryValidator;
    }

    public enum Status { SUCCESS, FALLBACK, FAILED }

    /** Pass2 调用结果：叙事行 + 谎徽标 + LLM 元信息 + 状态。 */
    public record Pass2Result(
            List<NarrativeLine> narrativeLines,
            Map<String, Boolean> lieFlags,
            LLMResponse llmResponse,
            Status status,
            String note,
            /** LLM 输出的隐藏剧情总结（B2：落库 story_summary 表用）。Fallback 时由本地构造。 */
            HiddenSummary hiddenSummary,
            /** Optional connective prose keyed to server-owned facts; null retains the legacy body protocol. */
            Map<String,String> narrativeBridges
    ) {
        public Pass2Result(List<NarrativeLine> lines,Map<String,Boolean> flags,LLMResponse response,
                Status status,String note,HiddenSummary summary) {
            this(lines,flags,response,status,note,summary,null);
        }
    }

    /**
     * 隐藏剧情总结（对应 story_summary 表的核心字段）。
     * LLM 输出 hidden_summary 块时解析；Fallback 时由本地按当前场景构造。
     */
    public record HiddenSummary(
            String locationName,
            Integer coordX,
            Integer coordY,
            String storyTime,
            String plotSummary,
            List<String> presentGods,
            List<String> keyEvents,
            String stateChanges
    ) {}

    /**
     * 基于规则结算 + 事实清单生成本回合叙事。
     * 即使 LLM 失败、解析失败，也一定返回非 null narrativeLines（走通用环境旁白）。
     */
    public Pass2Result generateNarrative(String outcomeType,
                                         String outcomeNote,
                                         String playerText,
                                         List<String> presentGodIds,
                                         List<String> factsChinese,
                                         String sceneSummary) {
        return generateNarrativeWithMemory(outcomeType, outcomeNote, playerText,
                presentGodIds, factsChinese, sceneSummary, "");
    }

    /** NPC decisions already contain their own words; narrator must not impersonate them again. */
    public Pass2Result generateEnsembleNarrativeWithMemory(String outcomeType, String outcomeNote,
            String playerText, List<String> presentGodIds, List<String> factsChinese,
            String sceneSummary, String memoryContext) {
        String context = (sceneSummary == null ? "" : sceneSummary)
                + "\n【旁白分工】真实在场人物索引：" + presentGodIds
                + (sceneSummary!=null && sceneSummary.contains("【正文时间锚点】")
                    ?"。本次只输出visible.bridges衔接对象，服务器自动依时序组织全部实际原话与事件。"
                    :"。NPC的公开台词必须通过提供的QUOTE引用交织进本次连续故事，本次visible.lines只输出godId=narrator的一条正文。")
                + "按已执行事实讲清事件先后与后果，不重新创作任何人物对白或私有心理，innerThought为空。"
                + "若提供正文时间锚点，改用visible.bridges对象：键是原始EVENT:编号或QUOTE:编号，值是放在该事实/台词之前的简短自然衔接。服务器自己按真实顺序插入全部事实与台词；不要输出完整speech或抄写标记和事实。每处不需补充时填空字符串或省略键，值只写0-30字、以逗号结尾的时空承接短语，不重新写动作与结果，不输出END，不替玩家决定。"
                + "hidden_summary仍只记实际事件，不将narrator当成世界中的人物。";
        return generateNarrativeWithMemory(outcomeType, outcomeNote, playerText,
                List.of("narrator"), factsChinese, context, memoryContext);
    }

    /**
     * Phase 9 记忆增强版：在 user prompt 头部注入玩家记忆段。
     * memoryContext 由 PlayerMemoryService.buildContextForPrompt 生成（ROUND Top3 + SCENE + GLOBAL）。
     */
    public Pass2Result generateNarrativeWithMemory(String outcomeType,
                                                    String outcomeNote,
                                                    String playerText,
                                                    List<String> presentGodIds,
                                                    List<String> factsChinese,
                                                    String sceneSummary,
                                                    String memoryContext) {
        String safeText = playerText == null ? "" : playerText;
        String tag = "scene-outcome=" + outcomeType;

        // 1) 调用 LLM（Phase 9：memoryContext 注入 user prompt 头部）
        LLMResponse llmResp;
        try {
            String userPrompt = Pass2PromptTemplate.userPrompt(
                    outcomeType, outcomeNote, safeText, presentGodIds, factsChinese, sceneSummary);
            // 记忆段非空时拼到 user prompt 头部
            if (memoryContext != null && !memoryContext.isBlank()) {
                userPrompt = memoryContext + "\n" + userPrompt;
            }
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content", (List.of("narrator").equals(presentGodIds) ? Pass2PromptTemplate.narratorSystemPrompt() : Pass2PromptTemplate.systemPrompt())),
                    Map.of("role", "user",   "content", userPrompt)
            );
            llmResp = LLMRequestPolicy.NARRATION.call(() -> llmProvider.chatForJson(msgs));
        } catch (LLMApiException e) {
            log.warn("[Pass2] LLM 调用失败 tag={} retriable={} msg={}", tag, e.isRetriable(), e.getMessage());
            return fallbackResult(tag, safeText, "LLM 调用失败，触发 Fallback：" + e.getMessage(),
                    presentGodIds, outcomeNote, factsChinese);
        }

        // 2) 解析 LLM 输出
        String parsedJson = llmResp.parsedJson();
        if (parsedJson == null) {
            parsedJson = LLMResponseParser.extractJsonOrNull(llmResp.content());
        }
        if (parsedJson == null) {
            log.warn("[Pass2] LLM 输出 JSON 解析失败 tag={} contentHead={}",
                    tag, truncate(llmResp.content(), 200));
            return fallbackResult(tag, safeText, "LLM 输出 JSON 解析失败，触发 Fallback",
                    presentGodIds, outcomeNote, factsChinese);
        }

        // 3) 反序列化为 NarrativeLine + lieFlags + hidden_summary
        try {
            JsonNode root = MAPPER.readTree(parsedJson);
            // 新 schema：visible.lines
            JsonNode visible = root.path("visible");
            JsonNode arr = visible.path("lines");
            Map<String,String> bridges=null;
            if(List.of("narrator").equals(presentGodIds) && sceneSummary!=null
                    && sceneSummary.contains("【正文时间锚点】") && visible.path("bridges").isObject()) {
                bridges=new LinkedHashMap<>();
                var fields=visible.path("bridges").fields();
                while(fields.hasNext() && bridges.size()<200) {
                    var field=fields.next();
                    if(field.getValue().isTextual())bridges.put(field.getKey(),field.getValue().asText());
                }
                // The sequence renderer supplies every fact even if the model omits every connective.
                arr=MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("godId","narrator").put("speech","正文由实际事件组成。"));
                log.info("[STORY-BRIDGES] returned={}",bridges.size());
            }
            // 兼容旧 schema：如果 visible 不存在，回退到 root.lines
            if (!arr.isArray() && root.path("lines").isArray()) {
                arr = root.path("lines");
            }
            if (!arr.isArray() || arr.isEmpty()) {
                log.warn("[Pass2] LLM 输出 visible.lines 为空或非数组 tag={}", tag);
                return fallbackResult(tag, safeText, "LLM 输出 visible.lines 为空，触发 Fallback",
                        presentGodIds, outcomeNote, factsChinese);
            }
            List<NarrativeLine> lines = new ArrayList<>();
            Map<String, Boolean> lieFlags = new LinkedHashMap<>();
            for (JsonNode ln : arr) {
                String godId = ln.path("godId").asText("");
                String speech = truncate(ln.path("speech").asText(""), 4000);
                String inner = truncate(ln.path("innerThought").asText(""), 4000);
                boolean lying = ln.path("isLying").asBoolean(false);
                if (godId.isBlank() || speech.isBlank() ||
                        (!"narrator".equals(godId) && (presentGodIds == null || !presentGodIds.contains(godId)))) continue;
                if (lines.size() >= 32) break;
                lines.add(new NarrativeLine(godId, speech, inner));
                lieFlags.merge(godId, lying, (a, b) -> a || b);
            }
            if (lines.isEmpty()) {
                return fallbackResult(tag, safeText, "模型未返回合法在场角色对白", presentGodIds, outcomeNote, factsChinese);
            }
            // 兜底：如果 LLM 漏了在场神明，补一条观望 line（防"情节不完整"）
            for (String gid : presentGodIds == null ? List.<String>of() : presentGodIds) {
                if (!lieFlags.containsKey(gid)) {
                    lines.add(new NarrativeLine(gid,
                            "（" + gid + " 默然不语，静观其变。场景氛围如常。）",
                            "本回合未发一言。"));
                    lieFlags.put(gid, false);
                }
            }
            // 解析 hidden_summary（委托 StorySummaryValidator 在 stage 边界完成 schema 校验）
            HiddenSummary hs = null;
            try {
                hs = summaryValidator.validate(root.path("hidden_summary"));
            } catch (Exception e) {
                // 校验失败不抛异常中断玩家体验，记 warn 后用 Fallback 版 hiddenSummary
                log.warn("[Pass2] hidden_summary 校验失败 tag={} msg={}，使用 Fallback 总结",
                        tag, e.getMessage());
                hs = buildFallbackSummary(presentGodIds, safeText);
            }
            logToken(tag, Status.SUCCESS, llmResp, safeText, "LLM 调用成功");
            return new Pass2Result(lines, lieFlags, llmResp, Status.SUCCESS, "LLM 调用成功", hs,bridges==null?null:Map.copyOf(bridges));
        } catch (Exception e) {
            log.warn("[Pass2] LLM 输出反序列化失败 tag={} msg={}", tag, e.getMessage());
            return fallbackResult(tag, safeText, "LLM 输出反序列化失败，触发 Fallback",
                    presentGodIds, outcomeNote, factsChinese);
        }
    }

    /** Fallback 版 hidden_summary：校验失败或 LLM 未输出时构造，保证落库不空。 */
    private HiddenSummary buildFallbackSummary(List<String> presentGodIds, String playerText) {
        return new HiddenSummary(
                "未知地点", 0, 0, "",
                "LLM Fallback：本回合剧情未生成，玩家输入=" + truncate(playerText, 60),
                presentGodIds == null ? List.of() : presentGodIds,
                List.of("LLM Fallback 触发"),
                "无");
    }

    /** Fallback 统一路径：解析 FallbackProvider 的通用旁白 JSON + 构造 Fallback 版 hidden_summary。 */
    private Pass2Result fallbackResult(String tag, String playerText, String note,
                                       List<String> presentGodIds, String outcomeNote, List<String> factsChinese) {
        String fbJson = FallbackProvider.fallbackPass2Json(playerText);
        LLMResponse fbResp = FallbackProvider.fallbackResponse(fbJson, fbJson);
        try {
            JsonNode root = MAPPER.readTree(fbJson);
            String speech = "本回合裁决：" + (outcomeNote == null ? "已结算" : outcomeNote)
                    + "。" + "\n" + root.path("displayedDialogue").asText("");
            String inner = root.path("truePsychologicalActivity").asText("");
            boolean lying = root.path("isLying").asBoolean(false);

            List<NarrativeLine> lines = new ArrayList<>();
            Map<String, Boolean> lieFlags = new LinkedHashMap<>();
            // Fallback 只产一条通用旁白（挂在玩家或第一个在场神明上）
            String hostId = (presentGodIds == null || presentGodIds.isEmpty())
                    ? "narrator" : presentGodIds.get(0);
            lines.add(new NarrativeLine(hostId, speech, inner));
            lieFlags.put(hostId, lying);
            // 其他在场神明补观望
            for (String gid : presentGodIds == null ? List.<String>of() : presentGodIds) {
                if (!lieFlags.containsKey(gid)) {
                    lines.add(new NarrativeLine(gid,
                            "（" + gid + " 未发一言，场景氛围如常。）",
                            "本回合 LLM 失败，回退至本地旁白。"));
                    lieFlags.put(gid, false);
                }
            }
            // Fallback 版 hidden_summary：地点名"未知"（坐标 0,0），plotSummary 用玩家原文
            HiddenSummary hs = new HiddenSummary(
                    "未知地点", 0, 0, "",
                    "LLM Fallback：本回合剧情未生成，玩家输入=" + truncate(playerText, 60),
                    presentGodIds == null ? List.of() : presentGodIds,
                    List.of("LLM Fallback 触发"),
                    "无");
            logToken(tag, Status.FALLBACK, fbResp, playerText, note);
            return new Pass2Result(lines, lieFlags, fbResp, Status.FALLBACK, note, hs);
        } catch (Exception e) {
            // 最终兜底：连 Fallback JSON 都解析失败（理论不应发生）
            List<NarrativeLine> lines = List.of(new NarrativeLine("narrator",
                    "风穿古碑，雨师凭栏，万物寂然。", "Fallback 兜底。"));
            Map<String, Boolean> flags = Map.of("narrator", false);
            LLMResponse r = FallbackProvider.fallbackResponse("{}", "{}");
            HiddenSummary hs = new HiddenSummary(
                    "未知地点", 0, 0, "",
                    "Fallback 解析失败兜底",
                    List.of(), List.of("Fallback 解析失败"), "无");
            logToken(tag, Status.FAILED, r, playerText, "Fallback 解析失败，已退至硬编码旁白");
            return new Pass2Result(lines, flags, r, Status.FAILED, "Fallback 解析失败", hs);
        }
    }

    /** 控制台 Token 日志（与 Pass1Service 同格式）。 */
    private void logToken(String tag, Status status, LLMResponse resp, String playerText, String note) {
        LLMConfig.PricePerMillion p = cfg.getPricePerMillion();
        log.info("""
                ╔══════════════════════════════════════════════════════════════╗
                ║ [LLM-Pass2] Token 消耗报告                                   ║
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
