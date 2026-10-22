package com.flowledger.bankintegration.statement;

import java.util.ArrayList;
import java.util.List;

/** Errors found in a statement file, capped so a badly broken file gives a readable answer. */
final class StatementErrors {

    static final int MAX_REPORTED_ERRORS = 100;

    final List<StatementError> list = new ArrayList<>();

    void add(Integer line, String field, String message) {
        if (list.size() < MAX_REPORTED_ERRORS) {
            list.add(new StatementError(line, field, message));
        }
    }

    boolean any() {
        return !list.isEmpty();
    }
}
