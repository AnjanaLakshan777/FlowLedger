package com.flowledger.forecast.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The company's cheque projection is not verified as complete right now, so no forecast is
 * calculated from it. The reason is a verification reason code, never cheque data.
 */
public class ChequeDataNotVerifiedException extends ErrorResponseException {

    public ChequeDataNotVerifiedException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(reason), null);
    }

    private static ProblemDetail problem(String reason) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Cheque data could not be confirmed as complete. Please try again later.");
        problem.setProperty("chequeVerification", reason);
        return problem;
    }
}
