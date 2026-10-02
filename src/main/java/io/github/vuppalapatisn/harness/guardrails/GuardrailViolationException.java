package io.github.vuppalapatisn.harness.guardrails;

public class GuardrailViolationException extends RuntimeException {

    public GuardrailViolationException(String message) {
        super(message);
    }
}
