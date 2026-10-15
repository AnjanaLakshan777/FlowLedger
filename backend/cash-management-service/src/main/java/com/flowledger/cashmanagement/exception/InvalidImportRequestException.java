package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.Map;

/**
 * A historical sales import request that cannot be accepted (no file, wrong type, missing reason),
 * reported in the same format as request validation errors.
 */
public class InvalidImportRequestException extends ErrorResponseException {

    public InvalidImportRequestException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, problem(field, message), null);
    }

    private static ProblemDetail problem(String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of(field, message));
        return problem;
    }
}
