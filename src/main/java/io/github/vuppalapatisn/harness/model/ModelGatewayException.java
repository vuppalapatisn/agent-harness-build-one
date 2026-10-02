package io.github.vuppalapatisn.harness.model;

public class ModelGatewayException extends RuntimeException {

    private final boolean retryable;

    public ModelGatewayException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** True for overload / rate-limit / 5xx / network failures, where failing over to another model helps. */
    public boolean isRetryable() {
        return retryable;
    }
}
