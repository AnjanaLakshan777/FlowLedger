package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class StatementImportAlreadyRemovedException extends ErrorResponseException {

    public StatementImportAlreadyRemovedException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This statement import has already been removed."),
                null);
    }
}
