package io.github.vuppalapatisn.harness.core;

import io.github.vuppalapatisn.harness.config.HarnessProperties.Route;
import io.github.vuppalapatisn.harness.cost.TokenBudgetService;
import io.github.vuppalapatisn.harness.memory.SessionStore;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import io.github.vuppalapatisn.harness.model.ContentPart;
import io.github.vuppalapatisn.harness.model.ModelGateway;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelRequest;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelResponse;
import io.github.vuppalapatisn.harness.model.ModelRouter;
import io.github.vuppalapatisn.harness.observability.HarnessTelemetry;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SUMMARIZATION memory strategy: condenses a session with the cheap {@code summarize} route
 * (Claude Haiku 4.5). Read-only over the session, so the stored history is never rewritten.
 */
@Service
public class SessionSummaryService {

    private static final String SYSTEM_PROMPT = """
            You summarize conversations between a user and a finance assistant. Write a concise summary \
            (at most 8 bullet points) of the questions asked, the figures found and any open follow-ups. \
            Do not invent numbers that are not in the transcript.""";

    private final SessionStore sessions;
    private final ModelGateway gateway;
    private final ModelRouter router;
    private final TokenBudgetService budget;
    private final HarnessTelemetry telemetry;

    public SessionSummaryService(SessionStore sessions, ModelGateway gateway, ModelRouter router,
                                 TokenBudgetService budget, HarnessTelemetry telemetry) {
        this.sessions = sessions;
        this.gateway = gateway;
        this.router = router;
        this.budget = budget;
        this.telemetry = telemetry;
    }

    public String summarize(String userId, String sessionId) {
        sessions.requireOwner(sessionId, userId);
        budget.ensureAvailable(userId);
        String transcript = render(sessions.load(sessionId));
        Route route = router.route(ModelRouter.SUMMARIZE);
        ModelRequest request = new ModelRequest(route.getModel(), SYSTEM_PROMPT,
                List.of(ChatMessage.userText("<transcript>\n" + transcript + "\n</transcript>")),
                List.of(), 1024, route.getEffort());
        ModelResponse response = telemetry.observeModelCall(route.getModel(), () -> gateway.complete(request));
        budget.record(userId, response.usage().total());
        telemetry.recordTokens(response.model(), response.usage().inputTokens(), response.usage().outputTokens());
        return response.message().text();
    }

    static String render(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            for (ContentPart part : m.parts()) {
                switch (part) {
                    case ContentPart.Text t -> sb.append(m.role()).append(": ").append(t.text()).append('\n');
                    case ContentPart.ToolCall c -> sb.append("TOOL CALL ").append(c.name()).append(' ').append(c.input()).append('\n');
                    case ContentPart.ToolResult r -> sb.append("TOOL RESULT: ").append(r.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
