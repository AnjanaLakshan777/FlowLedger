package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

// A month's transactions come from one import; importing them again would count them twice.
public class MonthAlreadyImportedException extends ErrorResponseException {

    public MonthAlreadyImportedException(String detail) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
    }
}
