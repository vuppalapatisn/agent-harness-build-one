package io.github.vuppalapatisn.harness.guardrails;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Input and output safety constraints applied outside the model. */
@Component
public class Guardrails {

    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern US_SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    private final HarnessProperties.Guardrails config;
    private final List<Pattern> blocked;

    public Guardrails(HarnessProperties properties) {
        this.config = properties.getGuardrails();
        this.blocked = config.getBlockedPatterns().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /** Rejects oversize / disallowed input and redacts PII before it reaches the model or memory. */
    public String checkInput(String input) {
        if (input == null || input.isBlank()) {
            throw new GuardrailViolationException("Message must not be empty");
        }
        if (input.length() > config.getMaxInputChars()) {
            throw new GuardrailViolationException("Message exceeds " + config.getMaxInputChars() + " characters");
        }
        for (Pattern p : blocked) {
            if (p.matcher(input).find()) {
                throw new GuardrailViolationException("Message violates content policy");
            }
        }
        return redact(input);
    }

    public String checkOutput(String output) {
        return redact(output);
    }

    private String redact(String text) {
        if (!config.isRedactPii()) {
            return text;
        }
        String out = CARD_NUMBER.matcher(text).replaceAll("[REDACTED_CARD]");
        out = US_SSN.matcher(out).replaceAll("[REDACTED_SSN]");
        return EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
    }
}
