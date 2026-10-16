package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class MembershipAlreadyExistsException extends ErrorResponseException {

    public MembershipAlreadyExistsException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This user is already a member of the company."),
                null);
    }
}
