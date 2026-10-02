package io.github.vuppalapatisn.harness.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Config-driven harness definition. Everything that is not genuinely agent-specific logic lives
 * here (system prompt, model routing, limits, tools, guardrails) so it can change without code.
 */
@Validated
@ConfigurationProperties(prefix = "harness")
public class HarnessProperties {

    @Valid private Agent agent = new Agent();
    @Valid private Limits limits = new Limits();
    @Valid private Models models = new Models();
    @Valid private Tools tools = new Tools();
    @Valid private Mcp mcp = new Mcp();
    @Valid private Guardrails guardrails = new Guardrails();

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public Models getModels() { return models; }
    public void setModels(Models models) { this.models = models; }
    public Tools getTools() { return tools; }
    public void setTools(Tools tools) { this.tools = tools; }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp mcp) { this.mcp = mcp; }
    public Guardrails getGuardrails() { return guardrails; }
    public void setGuardrails(Guardrails guardrails) { this.guardrails = guardrails; }

    public static class Agent {
        @NotBlank private String name = "FinBot";
        @NotBlank private String systemPrompt = "You are FinBot, a finance assistant.";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    }

    /** Hard limits that stop runaway loops (article: maxIterations / maxTokens / timeoutSeconds). */
    public static class Limits {
        @Min(1) private int maxIterations = 50;
        @Min(256) private long maxTokens = 8192;
        @Min(1) private long timeoutSeconds = 1800;
        @Min(1) private long dailyTokenBudgetPerUser = 5_000_000;
        @Min(1) private long toolTimeoutSeconds = 60;

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public long getMaxTokens() { return maxTokens; }
        public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public long getDailyTokenBudgetPerUser() { return dailyTokenBudgetPerUser; }
        public void setDailyTokenBudgetPerUser(long v) { this.dailyTokenBudgetPerUser = v; }
        public long getToolTimeoutSeconds() { return toolTimeoutSeconds; }
        public void setToolTimeoutSeconds(long toolTimeoutSeconds) { this.toolTimeoutSeconds = toolTimeoutSeconds; }
    }

    /** Model access abstraction: provider + per-use-case routes + pricing. */
    public static class Models {
        /** {@code anthropic} (Claude API) or {@code stub} (offline deterministic model for dev/CI). */
        @NotBlank private String provider = "anthropic";
        /** Opt into Claude API server-side refusal fallbacks. */
        private boolean refusalFallback = true;
        private Map<String, Route> routes = new LinkedHashMap<>();
        private Map<String, Price> pricing = new LinkedHashMap<>();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public boolean isRefusalFallback() { return refusalFallback; }
        public void setRefusalFallback(boolean refusalFallback) { this.refusalFallback = refusalFallback; }
        public Map<String, Route> getRoutes() { return routes; }
        public void setRoutes(Map<String, Route> routes) { this.routes = routes; }
        public Map<String, Price> getPricing() { return pricing; }
        public void setPricing(Map<String, Price> pricing) { this.pricing = pricing; }
    }

    public static class Route {
        @NotBlank private String model;
        /** low | medium | high | xhigh | max. Leave empty for models without effort (Haiku 4.5). */
        private String effort;

        public Route() {}
        public Route(String model, String effort) { this.model = model; this.effort = effort; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
    }

    /** USD per one million tokens. */
    public static class Price {
        private BigDecimal input = BigDecimal.ZERO;
        private BigDecimal output = BigDecimal.ZERO;

        public BigDecimal getInput() { return input; }
        public void setInput(BigDecimal input) { this.input = input; }
        public BigDecimal getOutput() { return output; }
        public void setOutput(BigDecimal output) { this.output = output; }
    }

    public static class Tools {
        /** Permission allowlist. Empty = every registered tool is allowed. */
        private List<String> allowed = new ArrayList<>();
        @Min(1) private int retrievalTopK = 4;

        public List<String> getAllowed() { return allowed; }
        public void setAllowed(List<String> allowed) { this.allowed = allowed; }
        public int getRetrievalTopK() { return retrievalTopK; }
        public void setRetrievalTopK(int retrievalTopK) { this.retrievalTopK = retrievalTopK; }
    }

    public static class Mcp {
        private List<McpServer> servers = new ArrayList<>();

        public List<McpServer> getServers() { return servers; }
        public void setServers(List<McpServer> servers) { this.servers = servers; }
    }

    public static class McpServer {
        @NotBlank private String name;
        @NotBlank private String url;
        private boolean enabled = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Guardrails {
        @Min(1) private int maxInputChars = 20_000;
        private List<String> blockedPatterns = new ArrayList<>();
        private boolean redactPii = true;

        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
        public List<String> getBlockedPatterns() { return blockedPatterns; }
        public void setBlockedPatterns(List<String> blockedPatterns) { this.blockedPatterns = blockedPatterns; }
        public boolean isRedactPii() { return redactPii; }
        public void setRedactPii(boolean redactPii) { this.redactPii = redactPii; }
    }
}
