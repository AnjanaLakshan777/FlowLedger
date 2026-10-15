package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class MembershipNotFoundException extends ErrorResponseException {

    public MembershipNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Membership not found."),
                null);
    }
}
