package io.github.vuppalapatisn.harness.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic offline model for local development and CI (no API key, no cost).
 *
 * <p>Behaviour: on a fresh user question it calls {@code search_filings} (if offered); once tool
 * results arrive it answers by quoting them. This exercises the full harness loop end to end.
 */
public class StubModelGateway implements ModelGateway {

    @Override
    public ModelResponse complete(ModelRequest request) {
        ChatMessage last = request.messages().get(request.messages().size() - 1);
        TokenUsage usage = new TokenUsage(estimateTokens(request), 20, 0, 0);

        boolean lastIsToolResults = last.parts().stream().anyMatch(ContentPart.ToolResult.class::isInstance);
        boolean canSearch = request.tools().stream().anyMatch(t -> t.name().equals("search_filings"));

        if (!lastIsToolResults && canSearch) {
            ObjectNode input = JsonNodeFactory.instance.objectNode().put("query", last.text());
            ContentPart call = new ContentPart.ToolCall("toolu_stub_" + UUID.randomUUID(), "search_filings", input);
            return new ModelResponse(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(call), null),
                    StopReason.TOOL_USE, usage, request.model());
        }

        String evidence = last.parts().stream()
                .filter(ContentPart.ToolResult.class::isInstance)
                .map(p -> ((ContentPart.ToolResult) p).content())
                .findFirst()
                .orElse(last.text());
        String answer = "[stub:" + request.model() + "] " + abbreviate(evidence, 600);
        return new ModelResponse(
                new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentPart.Text(answer)), null),
                StopReason.END_TURN, usage, request.model());
    }

    private static long estimateTokens(ModelRequest request) {
        long chars = request.systemPrompt().length();
        for (ChatMessage m : request.messages()) {
            chars += m.text().length();
        }
        return Math.max(1, chars / 4);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
