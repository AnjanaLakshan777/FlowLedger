package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DailySaleNotFoundException extends ErrorResponseException {

    public DailySaleNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Sales record not found."),
                null);
    }
}
