package io.github.vuppalapatisn.harness.cost;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.model.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Request-level cost from token metadata, using {@code harness.models.pricing} (USD per 1M tokens). */
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    /** Cache reads bill at ~10% of input, cache writes (5 min TTL) at 125%. */
    private static final BigDecimal CACHE_READ_FACTOR = new BigDecimal("0.10");
    private static final BigDecimal CACHE_WRITE_FACTOR = new BigDecimal("1.25");

    private final HarnessProperties properties;

    public CostCalculator(HarnessProperties properties) {
        this.properties = properties;
    }

    public BigDecimal cost(String model, TokenUsage usage) {
        HarnessProperties.Price price = properties.getModels().getPricing().get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal input = price.getInput().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.getInput().multiply(CACHE_READ_FACTOR).multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(price.getInput().multiply(CACHE_WRITE_FACTOR).multiply(BigDecimal.valueOf(usage.cacheWriteTokens())));
        BigDecimal output = price.getOutput().multiply(BigDecimal.valueOf(usage.outputTokens()));
        return input.add(output).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
