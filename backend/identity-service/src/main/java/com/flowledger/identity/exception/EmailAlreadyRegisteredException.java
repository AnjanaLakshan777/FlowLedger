package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class EmailAlreadyRegisteredException extends ErrorResponseException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists."),
                null);
    }
}
