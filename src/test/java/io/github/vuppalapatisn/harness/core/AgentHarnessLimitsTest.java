package io.github.vuppalapatisn.harness.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.vuppalapatisn.harness.cost.BudgetExceededException;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import io.github.vuppalapatisn.harness.model.ContentPart;
import io.github.vuppalapatisn.harness.model.ModelGateway;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelRequest;
import io.github.vuppalapatisn.harness.model.ModelGateway.ModelResponse;
import io.github.vuppalapatisn.harness.model.ModelGateway.StopReason;
import io.github.vuppalapatisn.harness.model.ModelGatewayException;
import io.github.vuppalapatisn.harness.model.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "harness.models.provider=stub",
        "harness.limits.max-iterations=3",
        "harness.limits.daily-token-budget-per-user=1000"
})
class AgentHarnessLimitsTest {

    @Autowired AgentHarness harness;
    @MockitoBean ModelGateway gateway;

    private static ModelResponse toolCall(String model) {
        ContentPart call = new ContentPart.ToolCall("toolu_1", "calculator",
                JsonNodeFactory.instance.objectNode().put("expression", "1+1"));
        return new ModelResponse(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(call), null),
                StopReason.TOOL_USE, new TokenUsage(10, 5, 0, 0), model);
    }

    private static ModelResponse answer(String model, String text, long tokens) {
        return new ModelResponse(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentPart.Text(text)), null),
                StopReason.END_TURN, new TokenUsage(tokens, 0, 0, 0), model);
    }

    @Test
    void stopsAtMaxIterations() {
        when(gateway.complete(any())).thenReturn(toolCall("claude-opus-5-5"));

        AgentHarness.InvocationResult result = harness.invoke("u-iter", null, "loop forever");

        assertThat(result.outcome()).isEqualTo(AgentHarness.Outcome.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.toolCalls()).hasSize(3).allMatch(c -> !c.error());
    }

    @Test
    void failsOverToFallbackModelOnOverload() {
        when(gateway.complete(any())).thenAnswer(inv -> {
            ModelRequest req = inv.getArgument(0);
            if (req.model().equals("claude-opus-5-5")) {
                throw new ModelGatewayException("529 overloaded", true, null);
            }
            return answer(req.model(), "served by fallback", 10);
        });

        AgentHarness.InvocationResult result = harness.invoke("u-failover", null, "hello");

        assertThat(result.outcome()).isEqualTo(AgentHarness.Outcome.COMPLETED);
        assertThat(result.model()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void nonRetryableErrorsAreNotFailedOver() {
        when(gateway.complete(any())).thenThrow(new ModelGatewayException("400 bad request", false, null));

        assertThatThrownBy(() -> harness.invoke("u-400", null, "hello")).isInstanceOf(ModelGatewayException.class);
    }

    @Test
    void rejectsUserOverDailyBudget() {
        when(gateway.complete(any())).thenReturn(answer("claude-opus-5-5", "big answer", 5_000));

        harness.invoke("u-budget", null, "first");  // consumes more than the 1000-token budget

        assertThatThrownBy(() -> harness.invoke("u-budget", null, "second"))
                .isInstanceOf(BudgetExceededException.class);
    }
}
