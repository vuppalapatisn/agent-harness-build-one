# Agent Harness on Spring Boot

A self-managed **agent harness** in Java/Spring Boot, built from the InfoQ article
[*Agent Harness: Build One*](https://www.infoq.com/articles/agent-harness-build-one/).

> **Agent = Model + Harness.** The model supplies the reasoning. The harness is everything around it
> that makes it a product: the loop, tools, memory, guardrails, cost limits, routing, and observability.

The example agent is **FinBot**, a finance assistant that answers questions from quarterly filings.
The filings are fictional sample data in `src/main/resources/filings/`.

**API docs:** with the app running, open [Swagger UI](http://localhost:8080/swagger-ui.html)
(raw spec: [`/v3/api-docs`](http://localhost:8080/v3/api-docs)).

## How the article maps to this code

| Article concept | Where it lives |
|---|---|
| System prompt + tool definitions (config-driven) | `application.yml` → `harness.agent`, `harness.tools` |
| Agent loop / orchestration | `core/AgentHarness.java` |
| Model access abstraction (swap providers) | `model/ModelGateway.java`, `AnthropicModelGateway`, `StubModelGateway` |
| Model routing + failover | `model/ModelRouter.java`, `harness.models.routes.{agent,fallback,summarize}` |
| Tools / MCP servers over HTTP | `tools/ToolRegistry.java`, `tools/McpToolProvider.java` (`harness.mcp.servers`) |
| RAG over filings (`k = 4`) | `tools/FilingsSearchTool.java` |
| Code sandbox | `tools/CalculatorTool.java` (a safe parser, not eval), or plug in a sandbox MCP server |
| Memory / checkpointing by `thread_id` | `memory/SessionStore.java` (H2 locally, Postgres in prod) |
| SUMMARIZATION memory strategy | `core/SessionSummaryService.java` |
| Cost control: `maxIterations=50`, `maxTokens=8192`, `timeoutSeconds=1800` | `harness.limits` |
| Per-user daily token budget → HTTP 429 | `cost/TokenBudgetService.java` |
| Per-request cost from token metadata | `cost/CostCalculator.java` |
| Guardrails | `guardrails/Guardrails.java`: input size, blocked patterns, PII redaction |
| Observability (`gen_ai.*` semantic conventions) | `observability/HarnessTelemetry.java` → Prometheus + OTLP traces |
| Credentials kept out of the agent process | API key read from the environment / GitHub secret only |

## Which model to use for what

All of this is configured in `harness.models.routes`. Changing a model needs no code change.

| Use case | Model | Why |
|---|---|---|
| **Main agent loop** (multi-step tool use, finance reasoning) — route `agent` | **Claude Opus 5.5** (`claude-opus-5-5`), effort `high` | Strongest model for long agentic tool use. As the article says, *a stronger model lifts everything above it*. Its effort setting defaults to `medium`, so the config sets `high` explicitly for agentic work. |
| **Failover** when the primary is overloaded, rate-limited, or returns 5xx — route `fallback` | **Claude Sonnet 5.5** (`claude-sonnet-5-5`), effort `medium` | Fast and capable at half of Opus's price. Keeps serving during an incident. |
| **Session summaries / high-volume, simple tasks** — route `summarize` | **Claude Haiku 4.5** (`claude-haiku-4-5`), no effort setting | Cheapest and fastest. Summarizing doesn't need deep reasoning. |
| **Refusal fallback** (safety classifier declines a request) | Server-side `fallbacks: "default"` | Turned on for Opus 5.5 and Sonnet 5.5 (`harness.models.refusal-fallback`). The Claude API picks the fallback model itself. |
| **Local dev and CI** | `stub` provider | Deterministic, offline, and free. CI never spends API credits. |
| **Hardest problems** (optional) | Claude Fable 5.1 (`claude-fable-5-1`) | Most capable model, at a higher price ($10 / $50 per 1M tokens). Use it only when an eval shows Opus falling short. |

Pricing per 1M input/output tokens: Opus 5.5 $4/$20, Sonnet 5.5 $2/$10, Haiku 4.5 $1/$5
(see `harness.models.pricing`).

Tuning tip: before adding more models, try **lowering effort** on Opus 5.5 (`medium`, then `low`)
for routes that don't need deep reasoning. One model at a lower effort often beats a multi-model
cascade, and it keeps a single prompt cache.

## Step by step

> On a Mac? Follow **[RUN_ON_MAC.md](RUN_ON_MAC.md)**, which covers Homebrew, the JDK, the Keychain-stored API key, and Docker/Colima.

### 1. Prerequisites
- JDK 21 and Maven 3.9+
- An Anthropic API key, needed only for real model calls: <https://console.anthropic.com>

### 2. Build and test (offline, no API key)
```bash
mvn verify
```

### 3. Run locally without an API key (stub model)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=stub
```

### 4. Run with Claude
```bash
export ANTHROPIC_API_KEY=sk-ant-...      # PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-..."
mvn spring-boot:run
```

### 5. Explore the API in Swagger UI
With the app running, open **<http://localhost:8080/swagger-ui.html>**. Every endpoint is documented
there, and you can call it directly with **Try it out** (set `X-User-Id` to any name, such as `alice`).

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI 3 spec (JSON): <http://localhost:8080/v3/api-docs>
- OpenAPI 3 spec (YAML): <http://localhost:8080/v3/api-docs.yaml>, for importing into Postman, Insomnia, or a client generator

### 6. Call the agent from the command line
```bash
curl -s -X POST localhost:8080/api/v1/agent/invoke \
  -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"message":"How much did revenue grow from Q1 to Q2 FY2026, in percent?"}'
```
The response includes `sessionId`, `answer`, `outcome` (`COMPLETED`, `MAX_ITERATIONS`, `TIMEOUT`,
`MAX_TOKENS`, `REFUSAL`, or `BUDGET_EXHAUSTED`), `iterations`, `model`, `usage`, `costUsd`, and `toolCalls`.
To continue the conversation, pass `"sessionId"` back in the next request.

| Endpoint | Purpose |
|---|---|
| `GET  /swagger-ui.html` | Interactive API docs (Swagger UI) |
| `GET  /v3/api-docs` | OpenAPI 3 spec |
| `POST /api/v1/agent/invoke` | Run the agent (`{sessionId?, message}`) |
| `GET  /api/v1/sessions/{id}` | Conversation history (checkpoint) |
| `POST /api/v1/sessions/{id}/summary` | Summarize the session with Haiku 4.5 |
| `GET  /api/v1/budget` | Remaining daily token budget for the caller |
| `GET  /actuator/prometheus` | `gen_ai_client_token_usage_total`, `gen_ai_client_operation_duration_*`, `harness_tool_duration_*`, `harness_model_failover_total` |

All `/api/v1` endpoints require the `X-User-Id` header. In production, put an authenticating
gateway or Spring Security OAuth2 resource server in front of the API.

### 7. Production-like stack (Postgres + OpenTelemetry collector)
```bash
ANTHROPIC_API_KEY=sk-ant-... docker compose up --build
```

### 8. Plug in MCP servers
Turn on the article's remote MCP servers with environment variables. Their tools appear to the model
as `filings__<tool>` and `sandbox__<tool>`:
```bash
FILINGS_MCP_ENABLED=true FILINGS_MCP_URL=http://filings-mcp:8000/mcp \
CODE_SANDBOX_MCP_ENABLED=true CODE_SANDBOX_MCP_URL=http://code-sandbox:8000/mcp \
mvn spring-boot:run
```
If a server can't be reached, the harness logs a warning and starts without it.

### 9. CI/CD (GitHub Actions)
- `.github/workflows/ci.yml` runs on every push and PR. It builds and tests with the stub model (no
  API cost) and uploads the jar. On `main`, it also builds and pushes a Docker image to
  `ghcr.io/<owner>/agent-harness-build-one`.
- `.github/workflows/live-smoke.yml` runs only when started manually. It boots the app against the
  real Claude API, asks FinBot a question, and checks that it used `search_filings` and completed.
  Before running it, add the repository secret **`ANTHROPIC_API_KEY`** (Settings → Secrets and
  variables → Actions). Then start it from the Actions tab → *Live smoke test* → *Run workflow*.

## Design notes
- **Append-only memory.** Claude assistant turns are stored as the SDK's own message JSON, including
  thinking blocks, and replayed byte for byte. History is never rewritten, so prompt caching and
  Claude's thinking replay keep working. Summaries are read-only views and don't replace history.
- **Parallel tool calls.** Tool calls run concurrently on virtual threads. All their results go back
  in one user message.
- **Graceful degradation.** Limits stop the loop cleanly with an `outcome` rather than an error. A
  failed model call is retried once on the fallback route. A failed tool returns `is_error` so the
  model can recover.
- **Prompt caching.** The system prompt and tools form a stable prefix with a cache breakpoint.
  Check `usage.cacheReadTokens` in responses.

## Next steps (kept out of scope, per the article's "don't gold-plate it")
- Replace the lexical retriever with a vector store (pgvector or OpenSearch).
- Move `TokenBudgetService` to Redis when running more than one replica.
- Add an eval suite (golden Q&A over the filings) as a CI gate before changing prompts or models.
- Add server-side compaction for very long sessions.
