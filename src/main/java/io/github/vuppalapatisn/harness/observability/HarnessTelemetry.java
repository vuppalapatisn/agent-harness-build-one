package io.github.vuppalapatisn.harness.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * One view of the prompt -> tool -> response chain. Metric and span names follow the OpenTelemetry
 * GenAI semantic conventions ({@code gen_ai.*}) so any OTLP backend can correlate them.
 */
@Component
public class HarnessTelemetry {

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public HarnessTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /** Wraps a model call in a {@code gen_ai.chat} span and records its duration. */
    public <T> T observeModelCall(String model, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return Observation.createNotStarted("gen_ai.chat", observations)
                    .contextualName("chat " + model)
                    .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                    .lowCardinalityKeyValue("gen_ai.request.model", model)
                    .observe(call);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            Timer.builder("gen_ai.client.operation.duration")
                    .tag("gen_ai.operation.name", "chat")
                    .tag("gen_ai.request.model", model)
                    .tag("outcome", outcome)
                    .register(meters)
                    .record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    public void recordTokens(String model, long input, long output) {
        tokenCounter(model, "input").increment(input);
        tokenCounter(model, "output").increment(output);
    }

    private Counter tokenCounter(String model, String type) {
        return Counter.builder("gen_ai.client.token.usage")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.token.type", type)
                .register(meters);
    }

    public void recordToolCall(String tool, boolean error, long durationMs) {
        Timer.builder("harness.tool.duration")
                .tag("gen_ai.tool.name", tool)
                .tag("outcome", error ? "error" : "success")
                .register(meters)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordStop(String stopReason) {
        Counter.builder("harness.invocations").tag("stop_reason", stopReason).register(meters).increment();
    }

    public void recordFailover(String from, String to) {
        Counter.builder("harness.model.failover").tag("from", from).tag("to", to).register(meters).increment();
    }
}
