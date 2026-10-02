package io.github.vuppalapatisn.harness.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    private final CalculatorTool tool;

    CalculatorToolTest() throws Exception {
        tool = new CalculatorTool(new ObjectMapper());
    }

    private String eval(String expression) {
        return tool.execute(JsonNodeFactory.instance.objectNode().put("expression", expression));
    }

    @Test
    void evaluatesWithPrecedenceAndParentheses() {
        assertThat(eval("2 + 3 * 4")).isEqualTo("14");
        assertThat(eval("(5120 - 4410) / 4410 * 100")).isEqualTo("16.099773");
        assertThat(eval("2^10")).isEqualTo("1024");
        assertThat(eval("-(3 - 5)")).isEqualTo("2");
        assertThat(eval("1,000 * 1.5")).isEqualTo("1500");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> eval("1 / 0")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> eval("Runtime.exec('x')")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("(1 + 2")).isInstanceOf(IllegalArgumentException.class);
    }
}
