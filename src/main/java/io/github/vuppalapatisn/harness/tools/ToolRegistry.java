package io.github.vuppalapatisn.harness.tools;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.model.ContentPart;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;
import io.github.vuppalapatisn.harness.observability.HarnessTelemetry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds every tool (local beans + MCP-discovered), enforces the permission allowlist and executes
 * parallel tool calls with a per-call timeout.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final HarnessProperties properties;
    private final HarnessTelemetry telemetry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(List<AgentTool> localTools, McpToolProvider mcpTools,
                        HarnessProperties properties, HarnessTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
        localTools.forEach(this::register);
        mcpTools.discover().forEach(this::register);
        log.info("Tools available to the agent: {}", specs().stream().map(ToolSpec::name).toList());
    }

    private void register(AgentTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            log.warn("Duplicate tool name '{}' ignored", tool.name());
        }
    }

    private boolean permitted(String name) {
        List<String> allowed = properties.getTools().getAllowed();
        return allowed.isEmpty() || allowed.contains(name);
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().filter(t -> permitted(t.name())).map(AgentTool::spec).toList();
    }

    public record Execution(ContentPart.ToolResult result, String toolName, long durationMs) {}

    /** Runs all tool calls of one assistant turn concurrently; results keep the call order. */
    public List<Execution> executeAll(List<ContentPart.ToolCall> calls) {
        long timeout = properties.getLimits().getToolTimeoutSeconds();
        List<CompletableFuture<Execution>> futures = new ArrayList<>();
        for (ContentPart.ToolCall call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> run(call), executor)
                    .completeOnTimeout(null, timeout, TimeUnit.SECONDS)
                    .thenApply(e -> e != null ? e : failed(call, "Tool timed out after " + timeout + "s", timeout * 1000)));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private Execution run(ContentPart.ToolCall call) {
        long start = System.nanoTime();
        AgentTool tool = tools.get(call.name());
        if (tool == null || !permitted(call.name())) {
            return failed(call, "Tool '" + call.name() + "' is not available or not permitted", 0);
        }
        try {
            String output = tool.execute(call.input());
            long ms = (System.nanoTime() - start) / 1_000_000;
            telemetry.recordToolCall(call.name(), false, ms);
            return new Execution(new ContentPart.ToolResult(call.id(), output, false), call.name(), ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String reason = e instanceof TimeoutException ? "timed out" : e.getMessage();
            return failed(call, "Tool error: " + reason, ms);
        }
    }

    private Execution failed(ContentPart.ToolCall call, String message, long ms) {
        telemetry.recordToolCall(call.name(), true, ms);
        return new Execution(new ContentPart.ToolResult(call.id(), message, true), call.name(), ms);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
