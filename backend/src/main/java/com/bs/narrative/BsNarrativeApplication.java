package com.bs.narrative;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 动态神话互动叙事系统 - 启动类
 *
 * <p>全局启用 @Async，用于 scene_summary 每 10 轮的后台异步摘要任务。
 */
@SpringBootApplication
@EnableAsync
public class BsNarrativeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BsNarrativeApplication.class, args);
    }
}
