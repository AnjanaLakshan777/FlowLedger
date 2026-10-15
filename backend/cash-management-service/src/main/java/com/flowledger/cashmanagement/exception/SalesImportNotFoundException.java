package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class SalesImportNotFoundException extends ErrorResponseException {

    public SalesImportNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Sales import not found."),
                null);
    }
}
