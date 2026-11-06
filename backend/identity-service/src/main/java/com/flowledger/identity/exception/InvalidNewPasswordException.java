package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.Map;

// Reported as a field error, like request validation.
public class InvalidNewPasswordException extends ErrorResponseException {

    public InvalidNewPasswordException(String message) {
        super(HttpStatus.BAD_REQUEST, problem(message), null);
    }

    private static ProblemDetail problem(String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of("newPassword", message));
        return problem;
    }
}
