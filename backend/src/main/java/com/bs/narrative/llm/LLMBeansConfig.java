package com.bs.narrative.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 DeepSeekLLMProvider 暴露成 Bean，让 Pass1Service/Pass2Service 后续 @Autowired 注入。
 * 注：FallbackProvider 和 LLMResponseParser 是纯静态方法，不需要 Bean。
 */
@Configuration
public class LLMBeansConfig {

    @Bean
    public LLMProvider llmProvider(LLMConfig cfg) {
        return new DeepSeekLLMProvider(cfg);
    }
}
