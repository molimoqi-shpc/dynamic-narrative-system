package com.bs.narrative.llm;

import java.util.function.Supplier;

/** Per-task request limits, without changing model interfaces or leaking across pooled threads. */
public enum LLMRequestPolicy {
    DEFAULT(0, 0, 0, false),
    INTENT(4096, 45, 65, true),
    NPC(6000, 45, 65, false),
    CHARACTER(10000, 60, 65, false),
    // The configured gateway produced whitespace-only JSON in thinking mode during real play.
    STORY_AUTHOR(12288, 60, 65, false),
    DIRECT_STORY(16384, 120, 125, false),
    STORY_FRAME(8192, 90, 95, false),
    STORY_TEXT(10000, 120, 125, false),
    NARRATION(4096, 45, 65, false),
    ACTION(8192, 45, 55, false),
    STORY_ARCHIVE(12288, 60, 65, false),
    STORY_TIME(1536, 30, 35, false),
    STORY_PUBLICATION(8192, 45, 50, false),
    // This gateway can spend the entire output allowance without a final answer in
    // thinking mode. Source-focused review uses its configured main-channel model.
    STORY_REVIEW(12288, 60, 65, false),
    ACCOUNT_ARCHIVE(4096, 45, 55, false),
    WAITING(384, 18, 20, false);
    private final int maxTokens, attemptSeconds, budgetSeconds;
    private final boolean thinking;
    LLMRequestPolicy(int tokens,int attempt,int budget,boolean thinking) {
        this.maxTokens=tokens;this.attemptSeconds=attempt;this.budgetSeconds=budget;this.thinking=thinking;
    }
    private static final ThreadLocal<LLMRequestPolicy> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<Long> OPERATION_DEADLINE=new ThreadLocal<>();
    public static long deadlineOr(long fallback) {var deadline=OPERATION_DEADLINE.get();return deadline==null?fallback:Math.min(deadline,fallback);}
    public static <T> T within(long deadline,Supplier<T> call) {
        Long previous=OPERATION_DEADLINE.get();OPERATION_DEADLINE.set(deadline);
        try {
            if(System.nanoTime()>=deadline)throw new LLMApiException("操作计算预算已结束",false);
            T value=call.get();
            if(System.nanoTime()>=deadline)throw new LLMApiException("操作结果超过计算期限，未接受",false);
            return value;
        }
        finally {if(previous==null)OPERATION_DEADLINE.remove();else OPERATION_DEADLINE.set(previous);}
    }
    public static LLMRequestPolicy current(){var p=CURRENT.get();return p==null?DEFAULT:p;}
    public int maxTokens(){return maxTokens;}
    public int attemptSeconds(){return attemptSeconds;}
    public int budgetSeconds(){return budgetSeconds;}
    public boolean thinking(){return thinking;}
    public <T> T call(Supplier<T> action){
        var previous=CURRENT.get();CURRENT.set(this);
        try{return action.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
