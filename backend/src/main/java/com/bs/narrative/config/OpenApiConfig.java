package com.bs.narrative.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 文档配置（毕设级：swagger-ui.html 可访问，论文截图用）。
 * 访问地址：http://localhost:8090/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("动态神话互动叙事系统 API")
                        .description("基于多智能体协同与限知博弈的互动叙事后端接口。\n\n" +
                                "## 核心流程\n" +
                                "1. Pass1：LLM 意图提取 → StructuredIntent\n" +
                                "2. 规则引擎：六维属性 + 三角干预 + 5 层优先级裁决 → ResolutionResult\n" +
                                "3. Pass2：LLM 古风叙事生成（visible 对白 + hidden_summary 隐藏锚点）\n" +
                                "4. StorySummary 落库 + 下次调用自动注入历史锚点")
                        .version("0.1.0")
                        .contact(new Contact().name("毕设项目").email("student@example.com"))
                        .license(new License().name("MIT License"))
                );
    }
}
