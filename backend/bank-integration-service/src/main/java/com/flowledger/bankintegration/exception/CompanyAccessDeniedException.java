package com.flowledger.bankintegration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class CompanyAccessDeniedException extends ErrorResponseException {

    public CompanyAccessDeniedException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have access to this company."),
                null);
    }
}
