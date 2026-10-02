package io.github.vuppalapatisn.harness.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.vuppalapatisn.harness.config.HarnessProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude API implementation of {@link ModelGateway} using the official Anthropic Java SDK.
 *
 * <p>Assistant turns are stored as the SDK's own {@link MessageParam} JSON and replayed verbatim, so
 * thinking blocks are preserved and history stays append-only.
 */
public class AnthropicModelGateway implements ModelGateway {

    /** Models that accept the {@code fallbacks: "default"} server-side refusal fallback. */
    private static final Set<String> FALLBACK_CAPABLE =
            Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private static final JsonMapper SDK_JSON = ObjectMappers.jsonMapper();

    private final AnthropicClient client;
    private final HarnessProperties properties;

    public AnthropicModelGateway(AnthropicClient client, HarnessProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        MessageCreateParams params = toParams(request);
        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            throw new ModelGatewayException(
                    "Claude API error " + status + ": " + e.getMessage(), status == 429 || status >= 500, e);
        } catch (AnthropicIoException e) {
            throw new ModelGatewayException("Claude API I/O error: " + e.getMessage(), true, e);
        }
        return fromResponse(response);
    }

    MessageCreateParams toParams(ModelRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                // Frozen system prompt first + cache breakpoint: stable prefix for prompt caching.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        if (request.effort() != null && !request.effort().isBlank()) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort())).build());
        }
        for (ToolSpec spec : request.tools()) {
            builder.addTool(toTool(spec));
        }
        for (MessageParam message : toMessageParams(request.messages())) {
            builder.addMessage(message);
        }
        if (properties.getModels().isRefusalFallback() && FALLBACK_CAPABLE.contains(request.model())) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        JsonNode properties = spec.inputSchema().path("properties");
        properties.fields().forEachRemaining(field ->
                props.putAdditionalProperty(field.getKey(), JsonValue.from(SDK_JSON.convertValue(field.getValue(), Object.class))));
        List<String> required = new ArrayList<>();
        spec.inputSchema().path("required").forEach(n -> required.add(n.asText()));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
                .build();
    }

    /** Converts harness messages, merging consecutive user turns (e.g. tool results + a new question). */
    private List<MessageParam> toMessageParams(List<ChatMessage> messages) {
        List<MessageParam> out = new ArrayList<>();
        List<ContentBlockParam> pendingUser = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == ChatMessage.Role.USER) {
                message.parts().forEach(p -> pendingUser.add(toBlock(p)));
                continue;
            }
            flushUser(out, pendingUser);
            out.add(assistantParam(message));
        }
        flushUser(out, pendingUser);
        return out;
    }

    private static void flushUser(List<MessageParam> out, List<ContentBlockParam> pending) {
        if (pending.isEmpty()) {
            return;
        }
        out.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.copyOf(pending)).build());
        pending.clear();
    }

    private static MessageParam assistantParam(ChatMessage message) {
        if (message.providerPayload() != null) {
            try {
                return SDK_JSON.readValue(message.providerPayload(), MessageParam.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupt stored assistant payload", e);
            }
        }
        List<ContentBlockParam> blocks = message.parts().stream().map(AnthropicModelGateway::toBlock).toList();
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build();
    }

    private static ContentBlockParam toBlock(ContentPart part) {
        return switch (part) {
            case ContentPart.Text t -> ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build());
            case ContentPart.ToolResult r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(r.toolCallId())
                    .content(r.content())
                    .isError(r.isError())
                    .build());
            case ContentPart.ToolCall c -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(c.id())
                    .name(c.name())
                    .input(JsonValue.from(SDK_JSON.convertValue(c.input(), Map.class)))
                    .build());
        };
    }

    ModelResponse fromResponse(Message response) {
        List<ContentPart> parts = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> parts.add(new ContentPart.Text(t.text())));
            block.toolUse().ifPresent(tu ->
                    parts.add(new ContentPart.ToolCall(tu.id(), tu.name(), SDK_JSON.valueToTree(tu._input()))));
        }
        String payload;
        try {
            payload = SDK_JSON.writeValueAsString(response.toParam());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize assistant message", e);
        }
        TokenUsage usage = new TokenUsage(
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));
        StopReason stop = response.stopReason().map(s -> mapStop(s.asString())).orElse(StopReason.OTHER);
        String servedBy = response.model().asString();
        return new ModelResponse(new ChatMessage(ChatMessage.Role.ASSISTANT, parts, payload), stop, usage, servedBy);
    }

    private static StopReason mapStop(String value) {
        return switch (value) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            case "pause_turn" -> StopReason.PAUSE_TURN;
            default -> StopReason.OTHER;
        };
    }
}
