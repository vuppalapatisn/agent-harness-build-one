package io.github.vuppalapatisn.harness.api;

import io.github.vuppalapatisn.harness.core.AgentHarness;
import io.github.vuppalapatisn.harness.core.AgentHarness.InvocationResult;
import io.github.vuppalapatisn.harness.core.SessionSummaryService;
import io.github.vuppalapatisn.harness.cost.TokenBudgetService;
import io.github.vuppalapatisn.harness.memory.SessionStore;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent API. The caller's identity arrives in {@code X-User-Id}; put an authenticating gateway
 * (OAuth2 resource server, API gateway) in front of this in production.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Agent", description = "Invoke the agent, inspect sessions and budgets")
public class HarnessController {

    private static final String USER_HEADER_DOC = "Caller identity; scopes sessions and the daily token budget";

    @Schema(description = "A message to the agent")
    public record InvokeRequest(
            @Schema(description = "Existing session to continue; omit to start a new one",
                    example = "3f2b1c9e-8a7d-4e21-9c55-0d6f1a2b3c4d", nullable = true)
            String sessionId,
            @Schema(description = "The user's message", example = "How much did total revenue grow from Q1 to Q2 FY2026, in percent?")
            @NotBlank String message) {}

    public record SummaryResponse(String sessionId, String summary) {}

    public record BudgetResponse(String userId, long dailyLimit, long used, long remaining) {}

    private final AgentHarness harness;
    private final SessionSummaryService summaries;
    private final SessionStore sessions;
    private final TokenBudgetService budget;

    public HarnessController(AgentHarness harness, SessionSummaryService summaries,
                             SessionStore sessions, TokenBudgetService budget) {
        this.harness = harness;
        this.summaries = summaries;
        this.sessions = sessions;
        this.budget = budget;
    }

    @PostMapping("/agent/invoke")
    @Operation(summary = "Run the agent",
            description = "Runs the agent loop (model -> tools -> model ...) until the model finishes or a harness "
                    + "limit stops it. The `outcome` field reports why the loop ended.")
    @ApiResponse(responseCode = "200", description = "Agent finished or was stopped by a harness limit")
    @ApiResponse(responseCode = "400", description = "Guardrail violation (empty, oversize or blocked input)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Session not found for this user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Daily token budget exhausted",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Model provider unavailable (after failover)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public InvocationResult invoke(
            @Parameter(description = USER_HEADER_DOC, example = "alice") @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody InvokeRequest request) {
        return harness.invoke(userId, request.sessionId(), request.message());
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Get session history", description = "The checkpointed, append-only conversation history.")
    @ApiResponse(responseCode = "404", description = "Session not found for this user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public List<ChatMessage> history(
            @Parameter(description = USER_HEADER_DOC, example = "alice") @RequestHeader("X-User-Id") String userId,
            @PathVariable String sessionId) {
        sessions.requireOwner(sessionId, userId);
        return sessions.load(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/summary")
    @Operation(summary = "Summarize a session",
            description = "Summarizes the session with the cheap `summarize` model route (Claude Haiku 4.5). "
                    + "Read-only: the stored history is not changed.")
    @ApiResponse(responseCode = "404", description = "Session not found for this user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public SummaryResponse summarize(
            @Parameter(description = USER_HEADER_DOC, example = "alice") @RequestHeader("X-User-Id") String userId,
            @PathVariable String sessionId) {
        return new SummaryResponse(sessionId, summaries.summarize(userId, sessionId));
    }

    @GetMapping("/budget")
    @Operation(summary = "Get token budget", description = "Today's token usage and remaining budget for the caller.")
    public BudgetResponse budget(
            @Parameter(description = USER_HEADER_DOC, example = "alice") @RequestHeader("X-User-Id") String userId) {
        return new BudgetResponse(userId, budget.dailyLimit(), budget.used(userId), budget.remaining(userId));
    }
}
