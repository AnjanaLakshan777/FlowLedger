package com.flowledger.identity.exception;

import com.flowledger.identity.entity.CompanyRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class RoleNotAllowedException extends ErrorResponseException {

    public RoleNotAllowedException(CompanyRole role) {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                        "The " + role + " role cannot be requested during self-registration."),
                null);
    }
}
