package io.github.vuppalapatisn.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Exact arithmetic for financial figures. A safe, sandboxed stand-in for the article's code
 * interpreter: a recursive-descent parser, no eval / scripting engine.
 */
@Component
public class CalculatorTool implements AgentTool {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final ToolSpec spec;

    public CalculatorTool(ObjectMapper mapper) throws IOException {
        this.spec = new ToolSpec(
                "calculator",
                "Evaluate an arithmetic expression exactly (decimal). Supports + - * / ^ and parentheses. "
                        + "Use it for growth rates, margins and any other computation instead of mental math.",
                mapper.readTree("""
                        {"type":"object",
                         "properties":{"expression":{"type":"string","description":"e.g. (5120-4410)/4410*100"}},
                         "required":["expression"]}
                        """));
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String execute(JsonNode input) {
        String expression = input.path("expression").asText("");
        BigDecimal result = new Parser(expression).parse();
        return result.setScale(Math.min(Math.max(result.scale(), 0), 6), RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** Grammar: expr := term (('+'|'-') term)* ; term := factor (('*'|'/') factor)* ; factor := unary ('^' factor)? */
    static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s.replace(" ", "").replace(",", "");
        }

        BigDecimal parse() {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("expression is required");
            }
            BigDecimal v = expr();
            if (pos != s.length()) {
                throw new IllegalArgumentException("Unexpected '" + s.charAt(pos) + "' at position " + pos);
            }
            return v;
        }

        private BigDecimal expr() {
            BigDecimal v = term();
            while (pos < s.length() && (peek() == '+' || peek() == '-')) {
                v = s.charAt(pos++) == '+' ? v.add(term(), MC) : v.subtract(term(), MC);
            }
            return v;
        }

        private BigDecimal term() {
            BigDecimal v = factor();
            while (pos < s.length() && (peek() == '*' || peek() == '/')) {
                if (s.charAt(pos++) == '*') {
                    v = v.multiply(factor(), MC);
                } else {
                    BigDecimal d = factor();
                    if (d.signum() == 0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    v = v.divide(d, MC);
                }
            }
            return v;
        }

        private BigDecimal factor() {
            BigDecimal base = unary();
            if (pos < s.length() && peek() == '^') {
                pos++;
                BigDecimal exp = factor();
                if (exp.stripTrailingZeros().scale() > 0 || exp.abs().compareTo(BigDecimal.valueOf(999)) > 0) {
                    throw new IllegalArgumentException("Exponent must be an integer between -999 and 999");
                }
                int e = exp.intValueExact();
                return e >= 0 ? base.pow(e, MC) : BigDecimal.ONE.divide(base.pow(-e, MC), MC);
            }
            return base;
        }

        private BigDecimal unary() {
            if (pos < s.length() && peek() == '-') {
                pos++;
                return unary().negate();
            }
            if (pos < s.length() && peek() == '(') {
                pos++;
                BigDecimal v = expr();
                if (pos >= s.length() || s.charAt(pos++) != ')') {
                    throw new IllegalArgumentException("Missing ')'");
                }
                return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(peek()) || peek() == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Number expected at position " + pos);
            }
            return new BigDecimal(s.substring(start, pos));
        }

        private char peek() {
            return s.charAt(pos);
        }
    }
}
