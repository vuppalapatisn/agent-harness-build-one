package io.github.vuppalapatisn.harness.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. Spec: {@code /v3/api-docs}, UI: {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI harnessOpenApi(HarnessProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Agent Harness API - " + properties.getAgent().getName())
                        .version("0.1.0")
                        .description("""
                                Self-managed agent harness (Agent = Model + Harness) on Spring Boot.

                                Every `/api/v1` call needs an `X-User-Id` header: it scopes sessions and the \
                                per-user daily token budget. Start with **POST /api/v1/agent/invoke**, then pass the \
                                returned `sessionId` back to continue the conversation.""")
                        .license(new License().name("Apache-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("InfoQ: Agent Harness - Build One")
                        .url("https://www.infoq.com/articles/agent-harness-build-one/"));
    }
}
