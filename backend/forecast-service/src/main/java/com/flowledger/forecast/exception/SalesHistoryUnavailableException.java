package com.flowledger.forecast.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class SalesHistoryUnavailableException extends ErrorResponseException {

    public SalesHistoryUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Sales history could not be loaded. Please try again later."),
                null);
    }
}
