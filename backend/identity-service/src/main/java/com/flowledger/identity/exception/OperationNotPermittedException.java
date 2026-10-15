package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class OperationNotPermittedException extends ErrorResponseException {

    public OperationNotPermittedException(String detail) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
    }
}
