package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

// Bank data must arrive the way the account's connection says: statements or manual entry, not both.
public class ConnectionModeMismatchException extends ErrorResponseException {

    public ConnectionModeMismatchException(String detail) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
    }
}
