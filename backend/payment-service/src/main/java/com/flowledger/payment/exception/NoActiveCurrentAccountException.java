package com.flowledger.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class NoActiveCurrentAccountException extends ErrorResponseException {

    public NoActiveCurrentAccountException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "The company has no active current account. Set one up before recording cheques."),
                null);
    }
}
