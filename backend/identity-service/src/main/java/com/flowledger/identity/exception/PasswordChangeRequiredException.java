package com.flowledger.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The email and password are right, but the account still has its temporary password. It is only
 * returned after the password matched, so it reveals nothing to someone without the password.
 */
public class PasswordChangeRequiredException extends ErrorResponseException {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    public PasswordChangeRequiredException() {
        super(HttpStatus.FORBIDDEN, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Choose a new password to replace the temporary password before signing in.");
        problem.setTitle("Password change required");
        problem.setProperty("code", CODE);
        return problem;
    }
}
