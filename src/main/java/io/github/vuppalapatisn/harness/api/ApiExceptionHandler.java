package io.github.vuppalapatisn.harness.api;

import io.github.vuppalapatisn.harness.cost.BudgetExceededException;
import io.github.vuppalapatisn.harness.guardrails.GuardrailViolationException;
import io.github.vuppalapatisn.harness.memory.SessionNotFoundException;
import io.github.vuppalapatisn.harness.model.ModelGatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GuardrailViolationException.class)
    ProblemDetail guardrail(GuardrailViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BudgetExceededException.class)
    ProblemDetail budget(BudgetExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    ProblemDetail session(SessionNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ModelGatewayException.class)
    ProblemDetail model(ModelGatewayException e) {
        HttpStatus status = e.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
