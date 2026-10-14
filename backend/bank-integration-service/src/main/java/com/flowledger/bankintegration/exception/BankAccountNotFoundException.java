package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class BankAccountNotFoundException extends ErrorResponseException {

    public BankAccountNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Bank account not found."),
                null);
    }
}
