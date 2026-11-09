package com.bs.narrative.llm;

/**
 * LLM 调用异常（HTTP 错误、超时、JSON 解析失败、超过 max-retries 等）。
 * 上层捕获后应直接走 FallbackProvider，不再重试。
 */
public class LLMApiException extends RuntimeException {
    private final boolean retriable;
    private String partialResponse="",finishReason="";
    public LLMApiException withPartialResponse(String response,String finish){this.partialResponse=java.util.Objects.toString(response,"");this.finishReason=java.util.Objects.toString(finish,"");return this;}
    public String partialResponse(){return partialResponse;}
    public String finishReason(){return finishReason;}

    public LLMApiException(String msg, boolean retriable, Throwable cause) {
        super(msg, cause);
        this.retriable = retriable;
    }
    public LLMApiException(String msg, boolean retriable) {
        this(msg, retriable, null);
    }
    public String category() {
        String value=java.util.Objects.toString(getMessage(),"").toLowerCase(java.util.Locale.ROOT);
        for(Throwable t=getCause();t!=null;t=t.getCause())value+=" "+t.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if("length".equals(finishReason) || value.contains("finish_reason=length"))return "MODEL_OUTPUT_TRUNCATED";
        if(value.contains("model_call_limit"))return "MODEL_CALL_LIMIT";
        if(value.matches("(?s).*\\b402\\b.*"))return "MODEL_PAYMENT_REQUIRED";
        if(value.matches("(?s).*\\b429\\b.*"))return "MODEL_RATE_LIMITED";
        if(value.matches("(?s).*\\b400\\b.*"))return "MODEL_REQUEST_INVALID";
        if(value.contains("timeout") || value.contains("timed out") || value.contains("超时"))return "MODEL_TIMEOUT";
        if(value.contains("connect") || value.contains("socket") || value.contains("连接"))return "MODEL_CONNECTION_FAILED";
        return "MODEL_UNAVAILABLE";
    }
    public boolean isRetriable() { return retriable; }
}
