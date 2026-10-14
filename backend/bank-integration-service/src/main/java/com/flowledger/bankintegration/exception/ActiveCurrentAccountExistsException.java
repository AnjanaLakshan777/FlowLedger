package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ActiveCurrentAccountExistsException extends ErrorResponseException {

    public ActiveCurrentAccountExistsException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "This company already has an active current account."),
                null);
    }
}
