package io.github.vuppalapatisn.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent = Model + Harness.
 *
 * <p>The model supplies reasoning; everything in this application is the harness: the agent loop,
 * tools (local + MCP), retrieval, session memory, guardrails, cost limits, model routing/failover
 * and gen_ai.* observability.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentHarnessApplication.class, args);
    }
}
