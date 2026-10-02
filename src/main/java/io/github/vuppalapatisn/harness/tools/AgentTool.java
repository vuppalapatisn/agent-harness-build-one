package io.github.vuppalapatisn.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;

/** A capability exposed to the model. Implementations are discovered as Spring beans. */
public interface AgentTool {

    ToolSpec spec();

    /** Executes the tool. Throwing marks the result {@code is_error} so the model can recover. */
    String execute(JsonNode input) throws Exception;

    default String name() {
        return spec().name();
    }
}
