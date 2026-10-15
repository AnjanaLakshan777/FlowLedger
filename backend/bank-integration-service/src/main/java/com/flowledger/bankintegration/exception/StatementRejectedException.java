package com.flowledger.bankintegration.exception;

import com.flowledger.bankintegration.statement.StatementError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.List;
import java.util.UUID;

/**
 * The statement could not be imported. Nothing was imported; the FAILED batch with these errors
 * is kept so the user can review it, correct the file and upload it again.
 */
public class StatementRejectedException extends ErrorResponseException {

    public StatementRejectedException(UUID batchId, List<StatementError> errors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(batchId, errors), null);
    }

    private static ProblemDetail problem(UUID batchId, List<StatementError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The statement was not imported because it contains errors. Correct the file and upload it again.");
        problem.setTitle("Statement rejected");
        problem.setProperty("batchId", batchId);
        problem.setProperty("errors", errors);
        return problem;
    }
}
