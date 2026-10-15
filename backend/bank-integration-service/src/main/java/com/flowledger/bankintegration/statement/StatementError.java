package com.flowledger.bankintegration.statement;

// A validation problem in an uploaded statement. line is the 1-based line in the file, or null
// for a problem with the whole file; field is the column name, or null.
public record StatementError(Integer line, String field, String message) {
}
