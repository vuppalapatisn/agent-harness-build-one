package io.github.vuppalapatisn.harness.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentPart.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ContentPart.ToolCall.class, name = "tool_call"),
    @JsonSubTypes.Type(value = ContentPart.ToolResult.class, name = "tool_result")
})
public sealed interface ContentPart {

    record Text(String text) implements ContentPart {}

    record ToolCall(String id, String name, JsonNode input) implements ContentPart {}

    record ToolResult(String toolCallId, String content, boolean isError) implements ContentPart {}
}
