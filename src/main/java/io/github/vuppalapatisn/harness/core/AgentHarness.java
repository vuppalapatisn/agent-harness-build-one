package io.github.vuppalapatisn.harness.core;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.config.HarnessProperties.Route;
import io.github.vuppalapatisn.harness.cost.CostCalculator;
import io.github.vuppalapatisn.harness.cost.TokenBudgetService;
import io.github.vuppalapatisn.harness.guardrails.Guardrails;
import io.github.vuppalapatisn.harness.memory.SessionStore;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import io.github.vuppalapatisn.harness.model.ModelGateway;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelRequest;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelResponse;
import io.github.vuppalapatisn.harness.model.ModelGateway.StopReason;
import io.github.vuppalapatisn.harness.model.ModelGatewayException;
import io.github.vuppalapatisn.harness.model.ModelRouter;
import io.github.vuppalapatisn.harness.model.TokenUsage;
import io.github.vuppalapatisn.harness.observability.HarnessTelemetry;
import io.github.vuppalapatisn.harness.tools.ToolRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The agent loop: call the model, run requested tools, feed results back, repeat until the model
 * finishes or a harness limit (iterations, wall-clock timeout, token budget) stops it.
 */
@Service
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    public enum Outcome { COMPLETED, MAX_ITERATIONS, TIMEOUT, MAX_TOKENS, REFUSAL, BUDGET_EXHAUSTED }

    public record ToolCallRecord(String name, boolean error, long durationMs) {}

    public record InvocationResult(
            String sessionId,
            String answer,
            Outcome outcome,
            int iterations,
            String model,
            TokenUsage usage,
            BigDecimal costUsd,
            List<ToolCallRecord> toolCalls) {}

    private final HarnessProperties properties;
    private final ModelGateway gateway;
    private final ModelRouter router;
    private final ToolRegistry tools;
    private final SessionStore sessions;
    private final Guardrails guardrails;
    private final TokenBudgetService budget;
    private final CostCalculator costs;
    private final HarnessTelemetry telemetry;
    private final Clock clock;

    public AgentHarness(HarnessProperties properties, ModelGateway gateway, ModelRouter router, ToolRegistry tools,
                        SessionStore sessions, Guardrails guardrails, TokenBudgetService budget,
                        CostCalculator costs, HarnessTelemetry telemetry, Clock clock) {
        this.properties = properties;
        this.gateway = gateway;
        this.router = router;
        this.tools = tools;
        this.sessions = sessions;
        this.guardrails = guardrails;
        this.budget = budget;
        this.costs = costs;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public InvocationResult invoke(String userId, String sessionId, String userMessage) {
        budget.ensureAvailable(userId);
        String input = guardrails.checkInput(userMessage);

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessions.create(userId);
        } else {
            sessions.requireOwner(sessionId, userId);
        }

        HarnessProperties.Limits limits = properties.getLimits();
        Instant deadline = Instant.now(clock).plusSeconds(limits.getTimeoutSeconds());
        List<ChatMessage> history = new ArrayList<>(sessions.load(sessionId));
        List<ChatMessage> turn = new ArrayList<>();
        turn.add(ChatMessage.userText(input));

        TokenUsage usage = TokenUsage.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        Outcome outcome = Outcome.MAX_ITERATIONS;
        String servedBy = router.route(ModelRouter.AGENT).getModel();
        String answer = "";
        int iteration = 0;

        try {
            while (iteration < limits.getMaxIterations()) {
                if (Instant.now(clock).isAfter(deadline)) {
                    outcome = Outcome.TIMEOUT;
                    break;
                }
                if (budget.remaining(userId) <= 0) {
                    outcome = Outcome.BUDGET_EXHAUSTED;
                    break;
                }
                iteration++;

                List<ChatMessage> context = new ArrayList<>(history);
                context.addAll(turn);
                ModelResponse response = callWithFailover(context);

                servedBy = response.model();
                usage = usage.plus(response.usage());
                cost = cost.add(costs.cost(response.model(), response.usage()));
                budget.record(userId, response.usage().total());
                telemetry.recordTokens(response.model(), response.usage().inputTokens(), response.usage().outputTokens());
                turn.add(response.message());

                if (response.stopReason() == StopReason.TOOL_USE && !response.message().toolCalls().isEmpty()) {
                    List<ToolRegistry.Execution> results = tools.executeAll(response.message().toolCalls());
                    results.forEach(r -> toolCalls.add(new ToolCallRecord(r.toolName(), r.result().isError(), r.durationMs())));
                    // All results of one turn go back in a single user message (keeps parallel tool use working).
                    turn.add(ChatMessage.user(results.stream().map(ToolRegistry.Execution::result).toList()));
                    continue;
                }
                if (response.stopReason() == StopReason.PAUSE_TURN) {
                    continue;
                }
                answer = response.message().text();
                outcome = switch (response.stopReason()) {
                    case MAX_TOKENS -> Outcome.MAX_TOKENS;
                    case REFUSAL -> Outcome.REFUSAL;
                    default -> Outcome.COMPLETED;
                };
                break;
            }
        } finally {
            // Checkpoint whatever happened, even on failure, so the session can be resumed.
            sessions.append(sessionId, turn);
        }

        if (outcome == Outcome.REFUSAL && answer.isBlank()) {
            answer = "I can't help with that request.";
        } else if (outcome != Outcome.COMPLETED && answer.isBlank()) {
            answer = "Stopped by harness limit: " + outcome + ". Send another message to continue.";
        }
        telemetry.recordStop(outcome.name());
        log.info("session={} user={} outcome={} iterations={} tokens={} cost=${}",
                sessionId, userId, outcome, iteration, usage.total(), cost);
        return new InvocationResult(sessionId, guardrails.checkOutput(answer), outcome, iteration, servedBy,
                usage, cost, toolCalls);
    }

    /** Primary route first; on overload / rate-limit / 5xx, fail over to the configured fallback route. */
    private ModelResponse callWithFailover(List<ChatMessage> context) {
        Route primary = router.route(ModelRouter.AGENT);
        try {
            return call(primary, context);
        } catch (ModelGatewayException e) {
            Optional<Route> fallback = router.fallback();
            if (!e.isRetryable() || fallback.isEmpty() || fallback.get().getModel().equals(primary.getModel())) {
                throw e;
            }
            log.warn("Model {} failed ({}); failing over to {}", primary.getModel(), e.getMessage(), fallback.get().getModel());
            telemetry.recordFailover(primary.getModel(), fallback.get().getModel());
            return call(fallback.get(), context);
        }
    }

    private ModelResponse call(Route route, List<ChatMessage> context) {
        ModelRequest request = new ModelRequest(
                route.getModel(),
                properties.getAgent().getSystemPrompt(),
                context,
                tools.specs(),
                properties.getLimits().getMaxTokens(),
                route.getEffort());
        return telemetry.observeModelCall(route.getModel(), () -> gateway.complete(request));
    }
}
