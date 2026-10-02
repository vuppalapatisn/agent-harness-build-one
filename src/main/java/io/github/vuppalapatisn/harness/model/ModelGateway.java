package io.github.vuppalapatisn.harness.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Unified model access layer. The harness never talks to a provider SDK directly, so models and
 * providers can be swapped by configuration.
 */
public interface ModelGateway {

    ModelResponse complete(ModelRequest request);

    record ModelRequest(
            String model,
            String systemPrompt,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            long maxTokens,
            String effort) {}

    record ModelResponse(ChatMessage message, StopReason stopReason, TokenUsage usage, String model) {}

    record ToolSpec(String name, String description, JsonNode inputSchema) {}

    enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE_TURN, OTHER }
}
