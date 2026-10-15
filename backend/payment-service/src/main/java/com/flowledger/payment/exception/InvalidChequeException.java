package com.flowledger.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.Map;

/**
 * A business-rule validation failure, reported in the same format as request validation
 * errors from GlobalExceptionHandler.
 */
public class InvalidChequeException extends ErrorResponseException {

    public InvalidChequeException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, problem(field, message), null);
    }

    private static ProblemDetail problem(String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of(field, message));
        return problem;
    }
}
