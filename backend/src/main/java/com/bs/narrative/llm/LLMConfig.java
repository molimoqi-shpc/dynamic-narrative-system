package com.bs.narrative.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 双通道配置（主副分离）。
 *
 * 主 LLM（v4-pro）：Pass1/Pass2/场景生成/角色创建/目标推进/死亡总结
 *   - 强推理，用于核心叙事生成
 * 副 LLM（v4-flash）：NPC 群戏批处理/打听辅助
 *   - 快速响应，用于角色扮演
 *
 * 两个通道各自带 backup-api-key（主 key 限流时自动切换）。
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
@ConfigurationPropertiesScan
public class LLMConfig {

    private String baseUrl;
    private int timeoutSeconds = 180;
    private int maxRetries = 2;
    private MainConfig main = new MainConfig();
    private SubConfig sub = new SubConfig();

    /** 主 LLM 配置（v4-pro）。 */
    public static class MainConfig {
        private String apiKey;
        private String backupApiKey;
        private String model;
        private PricePerMillion pricePerMillion = new PricePerMillion();
        private PricePerMillion flashPricePerMillion = prices(1,4);
        private PricePerMillion proPricePerMillion = prices(9,27);

        public PricePerMillion getFlashPricePerMillion(){return flashPricePerMillion;}
        public void setFlashPricePerMillion(PricePerMillion value){flashPricePerMillion=value;}
        public PricePerMillion getProPricePerMillion(){return proPricePerMillion;}
        public void setProPricePerMillion(PricePerMillion value){proPricePerMillion=value;}
        private static PricePerMillion prices(double prompt,double completion){var value=new PricePerMillion();value.setPrompt(prompt);value.setCompletion(completion);return value;}

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getBackupApiKey() { return backupApiKey; }
        public void setBackupApiKey(String v) { this.backupApiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public PricePerMillion getPricePerMillion() { return pricePerMillion; }
        public void setPricePerMillion(PricePerMillion v) { this.pricePerMillion = v; }
    }

    /** 副 LLM 配置（v4-flash）。 */
    public static class SubConfig {
        private String apiKey;
        private String backupApiKey;
        private String model;
        private PricePerMillion pricePerMillion = new PricePerMillion();

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getBackupApiKey() { return backupApiKey; }
        public void setBackupApiKey(String v) { this.backupApiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public PricePerMillion getPricePerMillion() { return pricePerMillion; }
        public void setPricePerMillion(PricePerMillion v) { this.pricePerMillion = v; }
    }

    public static class PricePerMillion {
        private double prompt = 2.0;
        private double completion = 8.0;
        public double getPrompt() { return prompt; }
        public void setPrompt(double v) { this.prompt = v; }
        public double getCompletion() { return completion; }
        public void setCompletion(double v) { this.completion = v; }
    }

    // ===== 全局配置 =====
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }

    // ===== 主副配置 =====
    public MainConfig getMain() { return main; }
    public void setMain(MainConfig v) { this.main = v; }
    public SubConfig getSub() { return sub; }
    public void setSub(SubConfig v) { this.sub = v; }

    // ===== 向后兼容旧代码（getApiKey/getModel 指向主 LLM）=====
    @Deprecated
    public String getApiKey() { return main.getApiKey(); }
    @Deprecated
    public void setApiKey(String v) { main.setApiKey(v); }
    @Deprecated
    public String getModel() { return main.getModel(); }
    @Deprecated
    public void setModel(String v) { main.setModel(v); }
    @Deprecated
    public PricePerMillion getPricePerMillion() { return main.getPricePerMillion(); }
    @Deprecated
    public void setPricePerMillion(PricePerMillion v) { main.setPricePerMillion(v); }
}
