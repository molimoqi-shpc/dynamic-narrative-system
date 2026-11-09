package com.bs.narrative.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;


/**
 * DeepSeek V4 OpenAI 兼容协议实现（POST /chat/completions）。
 *
 * 双通道设计（主副分离）：
 *   - chatMain()：主 LLM（v4-pro），用于 Pass1/Pass2/场景生成等核心叙事
 *   - chatSub()：副 LLM（v4-flash），用于 NPC 群戏等角色扮演
 *   - 默认 chat()/chatForJson() 走主通道（向后兼容）
 *
 * Key 轮换：主 key 限流/失败时自动切到 backup-api-key。
 *
 * 防坑规则2：调用 LLMResponseParser 兜底清洗 + max-retries 上限触发 Fallback。
 */
public class DeepSeekLLMProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLLMProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMConfig cfg;
    private final RestClient mainClient;
    private final RestClient subClient;

    private final boolean injectedClient;

    public DeepSeekLLMProvider(LLMConfig cfg) {
        this.cfg = cfg;
        this.injectedClient = false;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(cfg.getTimeoutSeconds()));

        this.mainClient = buildClient(cfg.getMain().getApiKey(), f);
        this.subClient = cfg.getSub().getApiKey() != null
                ? buildClient(cfg.getSub().getApiKey(), f) : mainClient;
    }

    /** 测试用构造：注入自定义 RestClient（mock）。 */
    public DeepSeekLLMProvider(LLMConfig cfg, RestClient mockClient) {
        this.cfg = cfg;
        this.injectedClient = true;
        this.mainClient = mockClient;
        this.subClient = mockClient;
    }

    private RestClient buildClient(String apiKey, SimpleClientHttpRequestFactory f) {
        return RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(f)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ===== 默认走主通道（向后兼容）=====

    @Override
    public LLMResponse chat(List<Map<String, String>> messages) {
        return doChat(messages, false, true);
    }

    @Override
    public LLMResponse chatForJson(List<Map<String, String>> messages) {
        return doChat(messages, true, true);
    }

    // ===== 主副双通道 =====

    /** 主 LLM（v4-pro）。 */
    public LLMResponse chatMain(List<Map<String, String>> messages) {
        return doChat(messages, false, true);
    }

    public LLMResponse chatMainForJson(List<Map<String, String>> messages) {
        return doChat(messages, true, true);
    }

    /** 副 LLM（v4-flash）。 */
    public LLMResponse chatSub(List<Map<String, String>> messages) {
        return doChat(messages, false, false);
    }

    public LLMResponse chatSubForJson(List<Map<String, String>> messages) {
        return doChat(messages, true, false);
    }

    // ===== 核心调用逻辑 =====

    private LLMResponse doChat(List<Map<String, String>> messages, boolean jsonMode, boolean useMain) {
        var policy=LLMRequestPolicy.current();
        String apiKey=useMain?cfg.getMain().getApiKey():cfg.getSub().getApiKey();
        String backupKey=useMain?cfg.getMain().getBackupApiKey():cfg.getSub().getBackupApiKey();
        String model=useMain?cfg.getMain().getModel():cfg.getSub().getModel();
        var price=useMain?cfg.getMain().getPricePerMillion():cfg.getSub().getPricePerMillion();
        if(useMain && MainModelContext.current()!=null){
            model=MainModelContext.current();
            if(!model.equals(cfg.getMain().getModel()))price=MainModelContext.PRO.equals(model)?cfg.getMain().getProPricePerMillion():cfg.getMain().getFlashPricePerMillion();
        }
        String channel=useMain?"main":"sub";
        if(apiKey==null || apiKey.isBlank() || "TODO_SET_YOUR_KEY_HERE".equals(apiKey))
            throw new LLMApiException("DeepSeek api-key 未配置 channel="+channel,false);
        Map<String,Object> body=new java.util.LinkedHashMap<>();
        var requestMessages=jsonMode?ModelProtocol.messages(ModelProtocol.policyStage(policy),messages):messages;
        body.put("model",model);body.put("messages",requestMessages);
        if(jsonMode)body.put("response_format",Map.of("type","json_object"));
        if(policy!=LLMRequestPolicy.DEFAULT){
            body.put("max_tokens",policy.maxTokens());
            body.put("thinking",Map.of("type",policy.thinking()?"enabled":"disabled"));
            if(policy.thinking())body.put("reasoning_effort","low");
        }
        int retries=Math.max(0,cfg.getMaxRetries());
        if(policy!=LLMRequestPolicy.DEFAULT)retries=Math.min(retries,1);
        int timeout=Math.max(1,cfg.getTimeoutSeconds());
        if(policy!=LLMRequestPolicy.DEFAULT)timeout=Math.min(timeout,policy.attemptSeconds());
        long started=System.nanoTime();
        long budgetSeconds=policy==LLMRequestPolicy.DEFAULT?(long)(timeout+10)*(retries+1):policy.budgetSeconds();
        long deadline=LLMRequestPolicy.deadlineOr(started+Duration.ofSeconds(budgetSeconds).toNanos());
        Exception last=null;boolean backup=false;
        for(int attempt=1;attempt<=retries+1;attempt++){
            long remainingMs=(deadline-System.nanoTime())/1_000_000;
            if(remainingMs<=0)break;
            long attemptStarted=System.nanoTime();
            String key=backup?backupKey:apiKey;
            RestClient client=injectedClient?(useMain?mainClient:subClient):buildClientWithKey(key,Math.min(timeout*1000L,remainingMs));
            try(var billing=LLMUsage.startCall(model)){
                String raw=client.post().uri("/chat/completions").header("Authorization","Bearer "+key)
                        .body(body).retrieve().body(String.class);
                var response=parseResponse(raw,jsonMode,price,model,billing);
                // Story and archive operations persist the paid original before parsing it.
                // Operation recovery owns retries; never discard or silently redraw a manuscript.
                if(jsonMode && response.parsedJson()==null && policy!=LLMRequestPolicy.STORY_AUTHOR && policy!=LLMRequestPolicy.STORY_ARCHIVE && policy!=LLMRequestPolicy.STORY_TIME && policy!=LLMRequestPolicy.STORY_PUBLICATION && policy!=LLMRequestPolicy.STORY_REVIEW
                        && !(policy==LLMRequestPolicy.CHARACTER && !response.content().isBlank()) && !(ModelProtocol.hasSpecificStage(requestMessages) && !response.content().isBlank()))
                    throw new LLMApiException("DeepSeek JSON 内容为空或不可解析",true);
                return response;
            }catch(org.springframework.web.client.HttpClientErrorException e){
                int status=e.getStatusCode().value();
                boolean hasBackup=backupKey!=null && !backupKey.isBlank() && !backupKey.equals(apiKey) && !backup;
                if((status==401 || status==403 || status==429) && hasBackup){backup=true;last=e;}
                else if(status==429){last=e;}
                else throw new LLMApiException("DeepSeek HTTP "+status+" channel="+channel,false);
            }catch(org.springframework.web.client.HttpServerErrorException e){
                int status=e.getStatusCode().value();
                if(status!=500 && status!=502 && status!=503 && status!=504)
                    throw new LLMApiException("DeepSeek HTTP "+status+" channel="+channel,false);
                last=e;
                if(backupKey!=null && !backupKey.isBlank() && !backupKey.equals(apiKey))backup=true;
            }catch(org.springframework.web.client.ResourceAccessException e){
                // Timeout/network response is ambiguous: immediate replay can duplicate charged generation.
                throw new LLMApiException("DeepSeek 网络异常或超时，停止本次请求 channel="+channel,false,e);
            }catch(LLMApiException e){
                if(!e.isRetriable())throw e;
                last=e;
            }catch(Exception e){
                throw new LLMApiException("DeepSeek 请求异常 channel="+channel,false,e);
            }finally{
                log.info("[LLM-TIMING] task={} channel={} attempt={} backup={} elapsedMs={} totalMs={}",
                        policy,channel,attempt,backup,(System.nanoTime()-attemptStarted)/1_000_000,(System.nanoTime()-started)/1_000_000);
            }
            if(attempt<=retries){
                long delay=Math.min(250L*attempt,Math.max(0,(deadline-System.nanoTime())/1_000_000));
                try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new LLMApiException("DeepSeek 请求已中断",false);}
            }
        }
        throw new LLMApiException("DeepSeek 请求预算或重试上限已达 task="+policy+" channel="+channel,true,last);
    }

    private RestClient buildClientWithKey(String apiKey,long timeoutMs) {
        SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(Math.min(5000,timeoutMs)));
        f.setReadTimeout(Duration.ofMillis(Math.max(1,timeoutMs)));
        return buildClient(apiKey,f);
    }

    private LLMResponse parseResponse(String raw, boolean jsonMode,
                                      LLMConfig.PricePerMillion price, String model, LLMUsage.Call billing) {
        try {
            JsonNode root = MAPPER.readTree(raw);
            boolean known=root.path("usage").path("prompt_tokens").isIntegralNumber() && root.path("usage").path("completion_tokens").isIntegralNumber() && root.path("usage").path("prompt_tokens").canConvertToInt() && root.path("usage").path("completion_tokens").canConvertToInt()
                    && root.path("usage").path("prompt_tokens").asLong()>=0 && root.path("usage").path("completion_tokens").asLong()>=0;
            int billedIn=Math.max(0,root.path("usage").path("prompt_tokens").asInt()),billedOut=Math.max(0,root.path("usage").path("completion_tokens").asInt());
            billing.response(new LLMResponse("",null,billedIn,billedOut,billedIn+billedOut,computeCost(billedIn,billedOut,price)),known);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LLMApiException("DeepSeek 返回 choices 为空 model=" + model, false);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            double cost = computeCost(promptTokens, completionTokens, price);

            String parsed = jsonMode ? LLMResponseParser.extractJsonOrNull(content) : null;
            LLMResponse resp = new LLMResponse(content, parsed, promptTokens, completionTokens,
                    promptTokens + completionTokens, cost);
            // Billing was already observed before validating the generated answer.
            String finish=choices.get(0).path("finish_reason").asText("");
            if("length".equals(finish) || "content_filter".equals(finish))
                throw new LLMApiException("DeepSeek 输出未完整结束 finish_reason="+finish+" model="+model,false).withPartialResponse(content,finish);

            // Token 日志（防坑规则2：固定格式报告）
            logTokenUsage(model, promptTokens, completionTokens, cost);
            return resp;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LLMApiException("DeepSeek 响应解析失败 model=" + model + ": " + e.getMessage(), false, e);
        }
    }

    private double computeCost(int in, int out, LLMConfig.PricePerMillion price) {
        return (in / 1_000_000.0) * price.getPrompt()
             + (out / 1_000_000.0) * price.getCompletion();
    }

    /** 防坑规则2：固定格式 Token 消耗报告。 */
    private void logTokenUsage(String model, int in, int out, double cost) {
        log.info("[LLM-TOKEN] model={} input={} output={} total={} cost={} CNY",
                model, in, out, in + out, String.format("%.4f", cost));
    }
}
