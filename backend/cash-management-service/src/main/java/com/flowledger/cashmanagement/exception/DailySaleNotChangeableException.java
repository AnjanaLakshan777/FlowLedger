package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The daily sale cannot be corrected: it came from an import, or someone changed it meanwhile. */
public class DailySaleNotChangeableException extends ErrorResponseException {

    private DailySaleNotChangeableException(String detail) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
    }

    public static DailySaleNotChangeableException imported() {
        return new DailySaleNotChangeableException("Sales from a historical import are corrected by reversing the import "
                + "and uploading a corrected file.");
    }

    public static DailySaleNotChangeableException stale() {
        return new DailySaleNotChangeableException("These sales were changed by someone else. Reload them and try again.");
    }
}
