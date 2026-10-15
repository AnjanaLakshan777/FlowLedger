package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DuplicateSaleDateException extends ErrorResponseException {

    public DuplicateSaleDateException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "Sales for this date have already been recorded."),
                null);
    }
}
