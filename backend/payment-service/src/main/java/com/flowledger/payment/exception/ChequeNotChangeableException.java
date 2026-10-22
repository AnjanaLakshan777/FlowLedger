package com.flowledger.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ChequeNotChangeableException extends ErrorResponseException {

    public ChequeNotChangeableException() {
        this("Only issued cheques whose expected clearing date is after today can be changed or cancelled.");
    }

    public ChequeNotChangeableException(String detail) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
    }
}
