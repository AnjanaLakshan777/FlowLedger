package com.flowledger.forecast.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ForecastRunNotFoundException extends ErrorResponseException {

    public ForecastRunNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                        "Forecast run not found."),
                null);
    }
}
