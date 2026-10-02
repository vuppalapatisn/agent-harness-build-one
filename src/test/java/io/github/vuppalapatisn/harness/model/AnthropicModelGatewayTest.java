package io.github.vuppalapatisn.harness.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelRequest;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies request mapping to the Claude Messages API without making network calls. */
class AnthropicModelGatewayTest {

    private final HarnessProperties props = new HarnessProperties();
    private final AnthropicModelGateway gateway = new AnthropicModelGateway(null, props);

    private ModelRequest request(String model, List<ChatMessage> messages) throws Exception {
        ToolSpec calc = new ToolSpec("calculator", "math", new ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"]}"));
        return new ModelRequest(model, "You are FinBot.", messages, List.of(calc), 8192, "high");
    }

    @Test
    void mergesConsecutiveUserTurnsAndMapsToolRoundTrip() throws Exception {
        ContentPart.ToolCall call = new ContentPart.ToolCall("toolu_1", "calculator",
                JsonNodeFactory.instance.objectNode().put("expression", "1+1"));
        List<ChatMessage> history = List.of(
                ChatMessage.userText("what is 1+1?"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(call), null),
                ChatMessage.user(List.of(new ContentPart.ToolResult("toolu_1", "2", false))),
                ChatMessage.userText("and 2+2?"));

        MessageCreateParams params = gateway.toParams(request("claude-opus-5-5", history));

        assertThat(params.messages()).hasSize(3);
        assertThat(params.messages().get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(params.messages().get(2).content().asBlockParams()).hasSize(2);
        assertThat(params.tools()).hasValueSatisfying(t -> assertThat(t).hasSize(1));
        assertThat(params.maxTokens()).isEqualTo(8192);
    }

    @Test
    void replaysAssistantTurnVerbatimIncludingThinking() throws Exception {
        String apiResponse = """
                {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[
                   {"type":"thinking","thinking":"","signature":"sig-abc"},
                   {"type":"text","text":"Let me check the filings."},
                   {"type":"tool_use","id":"toolu_9","name":"search_filings","input":{"query":"Q2 revenue"}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":40,"cache_read_input_tokens":100,"cache_creation_input_tokens":0}}
                """;
        Message message = com.anthropic.core.ObjectMappers.jsonMapper().readValue(apiResponse, Message.class);

        ModelGateway.ModelResponse response = gateway.fromResponse(message);

        assertThat(response.stopReason()).isEqualTo(ModelGateway.StopReason.TOOL_USE);
        assertThat(response.usage()).isEqualTo(new TokenUsage(120, 40, 100, 0));
        assertThat(response.message().toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.input().path("query").asText()).isEqualTo("Q2 revenue"));

        // Persist like SessionStore does, then rebuild the next request from storage.
        ObjectMapper appMapper = new ObjectMapper();
        ChatMessage stored = appMapper.readValue(appMapper.writeValueAsString(response.message()), ChatMessage.class);
        MessageCreateParams next = gateway.toParams(request("claude-opus-5-5", List.of(
                ChatMessage.userText("Q2 revenue?"),
                stored,
                ChatMessage.user(List.of(new ContentPart.ToolResult("toolu_9", "$5,120 million", false))))));

        List<com.anthropic.models.messages.ContentBlockParam> replayed =
                next.messages().get(1).content().asBlockParams();
        assertThat(replayed).hasSize(3);
        assertThat(replayed.get(0).thinking()).hasValueSatisfying(t -> assertThat(t.signature()).isEqualTo("sig-abc"));
        assertThat(replayed.get(2).toolUse()).hasValueSatisfying(t -> assertThat(t.id()).isEqualTo("toolu_9"));
    }

    @Test
    void enablesServerSideRefusalFallbackOnlyForSupportedModels() throws Exception {
        List<ChatMessage> history = List.of(ChatMessage.userText("hi"));

        MessageCreateParams opus = gateway.toParams(request("claude-opus-5-5", history));
        MessageCreateParams haiku = gateway.toParams(request("claude-haiku-4-5", history));

        assertThat(opus._additionalBodyProperties()).containsKey("fallbacks");
        assertThat(opus._headers().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        assertThat(haiku._additionalBodyProperties()).doesNotContainKey("fallbacks");
    }
}
