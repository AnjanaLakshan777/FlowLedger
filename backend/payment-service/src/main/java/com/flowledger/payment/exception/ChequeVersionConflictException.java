package com.flowledger.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ChequeVersionConflictException extends ErrorResponseException {

    public ChequeVersionConflictException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "The cheque was changed by someone else. Reload it and try again."),
                null);
    }
}
