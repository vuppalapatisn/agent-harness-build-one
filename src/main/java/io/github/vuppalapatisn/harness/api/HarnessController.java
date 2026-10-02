package io.github.vuppalapatisn.harness.api;

import io.github.vuppalapatisn.harness.core.AgentHarness;
import io.github.vuppalapatisn.harness.core.AgentHarness.InvocationResult;
import io.github.vuppalapatisn.harness.core.SessionSummaryService;
import io.github.vuppalapatisn.harness.cost.TokenBudgetService;
import io.github.vuppalapatisn.harness.memory.SessionStore;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
public class HarnessController {

    public record InvokeRequest(String sessionId, @NotBlank String message) {}

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
    public InvocationResult invoke(@RequestHeader("X-User-Id") String userId, @Valid @RequestBody InvokeRequest request) {
        return harness.invoke(userId, request.sessionId(), request.message());
    }

    @GetMapping("/sessions/{sessionId}")
    public List<ChatMessage> history(@RequestHeader("X-User-Id") String userId, @PathVariable String sessionId) {
        sessions.requireOwner(sessionId, userId);
        return sessions.load(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public SummaryResponse summarize(@RequestHeader("X-User-Id") String userId, @PathVariable String sessionId) {
        return new SummaryResponse(sessionId, summaries.summarize(userId, sessionId));
    }

    @GetMapping("/budget")
    public BudgetResponse budget(@RequestHeader("X-User-Id") String userId) {
        return new BudgetResponse(userId, budget.dailyLimit(), budget.used(userId), budget.remaining(userId));
    }
}
