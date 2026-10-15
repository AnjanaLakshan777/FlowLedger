package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidMembershipUpdateException extends ErrorResponseException {

    public InvalidMembershipUpdateException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
