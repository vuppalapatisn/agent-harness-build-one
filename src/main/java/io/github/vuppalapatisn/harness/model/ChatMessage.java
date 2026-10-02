package io.github.vuppalapatisn.harness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provider-neutral conversation message.
 *
 * @param providerPayload opaque provider-native serialization of an assistant turn (for Claude: the
 *     full message including thinking blocks). Gateways replay it verbatim so history stays
 *     append-only and unmodified.
 */
public record ChatMessage(Role role, List<ContentPart> parts, String providerPayload) {

    public enum Role { USER, ASSISTANT }

    public ChatMessage {
        parts = List.copyOf(parts);
    }

    public static ChatMessage userText(String text) {
        return new ChatMessage(Role.USER, List.of(new ContentPart.Text(text)), null);
    }

    public static ChatMessage user(List<? extends ContentPart> parts) {
        return new ChatMessage(Role.USER, List.copyOf(parts), null);
    }

    @JsonIgnore
    public String text() {
        return parts.stream()
                .filter(ContentPart.Text.class::isInstance)
                .map(p -> ((ContentPart.Text) p).text())
                .collect(Collectors.joining("\n"));
    }

    @JsonIgnore
    public List<ContentPart.ToolCall> toolCalls() {
        return parts.stream()
                .filter(ContentPart.ToolCall.class::isInstance)
                .map(ContentPart.ToolCall.class::cast)
                .toList();
    }
}
