package com.flowledger.forecast.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class PaymentServiceUnavailableException extends ErrorResponseException {

    public PaymentServiceUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cheque data could not be loaded. Please try again later."),
                null);
    }
}
