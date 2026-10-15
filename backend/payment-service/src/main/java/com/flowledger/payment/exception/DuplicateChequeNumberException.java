package com.flowledger.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DuplicateChequeNumberException extends ErrorResponseException {

    public DuplicateChequeNumberException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "This cheque number is already recorded for the company's current account."),
                null);
    }
}
