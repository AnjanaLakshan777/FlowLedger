package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.Map;

// A new account needs a temporary password. Reported as a field error, like request validation.
public class TemporaryPasswordRequiredException extends ErrorResponseException {

    public TemporaryPasswordRequiredException() {
        super(HttpStatus.BAD_REQUEST, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors",
                Map.of("temporaryPassword", "A temporary password is required for a new account."));
        return problem;
    }
}
