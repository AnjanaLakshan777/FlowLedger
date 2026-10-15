package com.flowledger.cashmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The import cannot be uploaded, approved or rejected in its current state: the same file is
 * already waiting or approved, the batch was changed or decided meanwhile, or some of its dates
 * already have recorded sales. Nothing was changed.
 */
public class SalesImportConflictException extends ErrorResponseException {

    private SalesImportConflictException(ProblemDetail problem) {
        super(HttpStatus.CONFLICT, problem, null);
    }

    public static SalesImportConflictException duplicateFile(UUID existingBatchId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This file has already been uploaded and is waiting for approval or approved.");
        if (existingBatchId != null) {
            problem.setProperty("existingImportId", existingBatchId);
        }
        return new SalesImportConflictException(problem);
    }

    public static SalesImportConflictException notPending() {
        return new SalesImportConflictException(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This import is not waiting for approval."));
    }

    public static SalesImportConflictException stale() {
        return new SalesImportConflictException(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This import was changed by someone else. Reload it and try again."));
    }

    public static SalesImportConflictException datesAlreadyRecorded(List<LocalDate> dates) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Sales are already recorded for " + dates.size() + " of the dates in this import. "
                        + "Nothing was imported; reject it and upload a corrected file.");
        problem.setProperty("conflictingDates", dates);
        return new SalesImportConflictException(problem);
    }
}
