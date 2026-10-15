package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class LastCompanyAdminException extends ErrorResponseException {

    public LastCompanyAdminException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "This is the company's last active company admin."),
                null);
    }
}
