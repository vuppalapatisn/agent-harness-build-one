package io.github.vuppalapatisn.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.config.HarnessProperties.McpServer;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal MCP client over Streamable HTTP (JSON-RPC 2.0): {@code initialize}, {@code tools/list},
 * {@code tools/call}. Each remote tool is exposed to the model as {@code <server>__<tool>}.
 * Unreachable servers are logged and skipped so the agent still starts (graceful degradation).
 */
@Component
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final HarnessProperties properties;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final AtomicLong ids = new AtomicLong();

    public McpToolProvider(HarnessProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = builder.build();
    }

    public List<AgentTool> discover() {
        List<AgentTool> tools = new ArrayList<>();
        for (McpServer server : properties.getMcp().getServers()) {
            if (!server.isEnabled()) {
                continue;
            }
            try {
                Session session = initialize(server);
                JsonNode list = session.call("tools/list", mapper.createObjectNode());
                for (JsonNode t : list.path("tools")) {
                    tools.add(new RemoteTool(session, t.path("name").asText(), new ToolSpec(
                            server.getName() + "__" + t.path("name").asText(),
                            t.path("description").asText("MCP tool from " + server.getName()),
                            t.path("inputSchema"))));
                }
                log.info("MCP server '{}' contributed {} tools", server.getName(), list.path("tools").size());
            } catch (Exception e) {
                log.warn("MCP server '{}' at {} unavailable: {}", server.getName(), server.getUrl(), e.getMessage());
            }
        }
        return tools;
    }

    private Session initialize(McpServer server) throws IOException {
        Session session = new Session(server, null);
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "agent-harness").put("version", "0.1.0");
        ResponseEntity<String> response = session.post(rpc("initialize", params));
        Session ready = new Session(server, response.getHeaders().getFirst("Mcp-Session-Id"));
        ready.post(mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        return ready;
    }

    private ObjectNode rpc(String method, JsonNode params) {
        ObjectNode node = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
        node.set("params", params);
        return node;
    }

    private final class Session {
        private final McpServer server;
        private final String sessionId;

        Session(McpServer server, String sessionId) {
            this.server = server;
            this.sessionId = sessionId;
        }

        ResponseEntity<String> post(JsonNode body) {
            return http.post()
                    .uri(server.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .headers(h -> { if (sessionId != null) h.set("Mcp-Session-Id", sessionId); })
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
        }

        JsonNode call(String method, JsonNode params) throws IOException {
            ResponseEntity<String> response = post(rpc(method, params));
            JsonNode envelope = parse(response);
            if (envelope.has("error")) {
                throw new IOException("MCP error: " + envelope.path("error").path("message").asText());
            }
            return envelope.path("result");
        }

        /** Accepts both plain JSON and SSE-framed ({@code data: {...}}) responses. */
        private JsonNode parse(ResponseEntity<String> response) throws IOException {
            String body = response.getBody() == null ? "" : response.getBody();
            MediaType type = response.getHeaders().getContentType();
            if (type != null && type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
                for (String line : body.split("\\R")) {
                    if (line.startsWith("data:")) {
                        JsonNode node = mapper.readTree(line.substring(5).strip());
                        if (node.has("result") || node.has("error")) {
                            return node;
                        }
                    }
                }
                throw new IOException("No JSON-RPC response in SSE stream");
            }
            return mapper.readTree(body);
        }
    }

    private final class RemoteTool implements AgentTool {
        private final Session session;
        private final String remoteName;
        private final ToolSpec spec;

        RemoteTool(Session session, String remoteName, ToolSpec spec) {
            this.session = session;
            this.remoteName = remoteName;
            this.spec = spec;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public String execute(JsonNode input) throws IOException {
            ObjectNode params = mapper.createObjectNode().put("name", remoteName);
            params.set("arguments", input);
            JsonNode result = session.call("tools/call", params);
            StringBuilder out = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                out.append(c.path("type").asText().equals("text") ? c.path("text").asText() : c.toString()).append('\n');
            }
            if (result.path("isError").asBoolean(false)) {
                throw new IOException(out.toString().strip());
            }
            return out.toString().strip();
        }
    }
}
