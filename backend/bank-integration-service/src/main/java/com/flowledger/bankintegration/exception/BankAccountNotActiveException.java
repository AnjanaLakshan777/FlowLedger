package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class BankAccountNotActiveException extends ErrorResponseException {

    public BankAccountNotActiveException() {
        this("Statements can only be imported into an active current account.");
    }

    public BankAccountNotActiveException(String detail) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
    }
}
