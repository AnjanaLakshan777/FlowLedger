package com.flowledger.forecast.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class BalanceNotAvailableException extends ErrorResponseException {

    public BalanceNotAvailableException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "No confirmed LKR bank balance is available for the company's current account. "
                                + "Import a bank statement first."),
                null);
    }
}
