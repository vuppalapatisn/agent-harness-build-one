# Running the Agent Harness on macOS

Works on Apple Silicon (M1–M4) and Intel Macs. Run every command in **Terminal** (or iTerm2).

## 1. Install the prerequisites

### 1.1 Homebrew (skip if `brew --version` already works)
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```
On Apple Silicon, put Homebrew on your PATH. The installer prints this command at the end:
```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile && eval "$(/opt/homebrew/bin/brew shellenv)"
```

### 1.2 Java 21, Maven, Git, and jq
```bash
brew install --cask temurin@21
brew install maven git jq
```

Point `JAVA_HOME` at JDK 21 (zsh is the default shell on macOS):
```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc && source ~/.zshrc
```

Check the versions:
```bash
java -version
mvn -v
```
Both should report Java **21**. If `mvn -v` shows a different Java, check that `JAVA_HOME` is set as above.

## 2. Get the code
```bash
git clone https://github.com/vuppalapatisn/agent-harness-build-one.git
cd agent-harness-build-one
```

## 3. Build and run the tests (offline, no API key)
```bash
mvn verify
```
You should see `Tests run: 17, Failures: 0` and `BUILD SUCCESS`. The tests use the built-in stub model, so they cost nothing.

## 4. Run without an API key (stub model)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=stub
```
Wait for `Started AgentHarnessApplication`. Then open a **second Terminal tab** (⌘T) and call the agent:
```bash
curl -s -X POST localhost:8080/api/v1/agent/invoke -H 'Content-Type: application/json' -H 'X-User-Id: alice' -d '{"message":"What was gross margin in Q2 FY2026?"}' | jq .
```
In stub mode, the answer is the matching filing passages, prefixed with `[stub:claude-opus-5-5]`.

Prefer a browser? Open the Swagger UI and use **Try it out** on any endpoint:
```bash
open http://localhost:8080/swagger-ui.html
```

Press **Ctrl+C** in the first tab to stop the server.

## 5. Run with Claude (real model)

### 5.1 Set your API key
Create a key at <https://console.anthropic.com>, then set it for the current Terminal session:
```bash
export ANTHROPIC_API_KEY='sk-ant-...'
```
To keep the key across sessions, store it in the macOS Keychain instead of a plain-text file:
```bash
security add-generic-password -a "$USER" -s anthropic-api-key -w 'sk-ant-...'
```
```bash
echo 'export ANTHROPIC_API_KEY=$(security find-generic-password -a "$USER" -s anthropic-api-key -w)' >> ~/.zshrc && source ~/.zshrc
```

### 5.2 Start the app
```bash
mvn spring-boot:run
```

### 5.3 Ask FinBot a question (second tab)
```bash
curl -s -X POST localhost:8080/api/v1/agent/invoke -H 'Content-Type: application/json' -H 'X-User-Id: alice' -d '{"message":"How much did total revenue grow from Q1 to Q2 FY2026, in percent?"}' | tee /tmp/r.json | jq .
```
To continue the same conversation, pass the `sessionId` back:
```bash
curl -s -X POST localhost:8080/api/v1/agent/invoke -H 'Content-Type: application/json' -H 'X-User-Id: alice' -d "{\"sessionId\":\"$(jq -r .sessionId /tmp/r.json)\",\"message\":\"And the operating margin change?\"}" | jq .
```

### 5.4 Other endpoints
```bash
curl -s localhost:8080/api/v1/sessions/$(jq -r .sessionId /tmp/r.json) -H 'X-User-Id: alice' | jq .
```
```bash
curl -s -X POST localhost:8080/api/v1/sessions/$(jq -r .sessionId /tmp/r.json)/summary -H 'X-User-Id: alice' | jq .
```
```bash
curl -s localhost:8080/api/v1/budget -H 'X-User-Id: alice' | jq .
```
```bash
curl -s localhost:8080/actuator/prometheus | grep '^gen_ai_'
```

## 6. Optional: run as a jar
```bash
mvn -q package -DskipTests
```
```bash
java -jar target/agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=stub
```
To use Claude, leave out `--spring.profiles.active=stub` and set `ANTHROPIC_API_KEY` first.

## 7. Optional: full stack with Docker (Postgres + OpenTelemetry collector)
Install Docker Desktop for Mac and start it. If you prefer a lightweight alternative, Colima works too:
```bash
brew install --cask docker
```
```bash
brew install colima docker docker-compose && colima start
```
Then build and run the stack:
```bash
docker compose up --build
```
All images support both Apple Silicon (arm64) and Intel (amd64). Stop the stack with Ctrl+C, then:
```bash
docker compose down
```

## 8. Open in an IDE
- **IntelliJ IDEA:** File → Open → select `pom.xml` → Open as Project. Set Project SDK to 21. Run
  `AgentHarnessApplication`. To use the stub model, add `stub` under Active profiles.
- **VS Code:** install the "Extension Pack for Java" and "Spring Boot Extension Pack" extensions,
  open the folder, then run from the Spring Boot Dashboard.

## Troubleshooting
| Symptom | Fix |
|---|---|
| `release version 21 not supported` | Maven is using an older JDK. Run `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. |
| `Port 8080 already in use` | Find the process with `lsof -i :8080`, or run with `-Dspring-boot.run.arguments=--server.port=8081`. |
| HTTP 401 / `authentication_error` from Claude | `ANTHROPIC_API_KEY` is missing or wrong in *this* tab. Check with `echo ${ANTHROPIC_API_KEY:0:7}`. |
| HTTP 429 from `/agent/invoke` | That user has used up the daily token budget (`harness.limits.daily-token-budget-per-user`). |
| `zsh: command not found: brew` | Run the `brew shellenv` line from step 1.1. |
| Docker: `Cannot connect to the Docker daemon` | Start Docker Desktop, or run `colima start`. |
