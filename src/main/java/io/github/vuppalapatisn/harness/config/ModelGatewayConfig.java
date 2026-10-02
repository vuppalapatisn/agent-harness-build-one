package io.github.vuppalapatisn.harness.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.github.vuppalapatisn.harness.model.AnthropicModelGateway;
import io.github.vuppalapatisn.harness.model.ModelGateway;
import io.github.vuppalapatisn.harness.model.StubModelGateway;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the model provider from {@code harness.models.provider}. Credentials never enter app code. */
@Configuration
public class ModelGatewayConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "harness.models.provider", havingValue = "anthropic", matchIfMissing = true)
    AnthropicClient anthropicClient() {
        // Reads ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL from the environment.
        return AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofMinutes(10))
                .maxRetries(2)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "harness.models.provider", havingValue = "anthropic", matchIfMissing = true)
    ModelGateway anthropicModelGateway(AnthropicClient client, HarnessProperties properties) {
        return new AnthropicModelGateway(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "harness.models.provider", havingValue = "stub")
    ModelGateway stubModelGateway() {
        return new StubModelGateway();
    }
}
