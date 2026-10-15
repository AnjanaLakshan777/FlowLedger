package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class BalanceNotAvailableException extends ErrorResponseException {

    public BalanceNotAvailableException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                        "No confirmed balance has been recorded for this bank account."),
                null);
    }
}
