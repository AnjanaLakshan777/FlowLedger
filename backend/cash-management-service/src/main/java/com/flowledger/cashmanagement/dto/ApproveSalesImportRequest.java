package com.flowledger.cashmanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param version            the import version the approver reviewed; a changed import is refused
 * @param excludeInvalidRows must be true to approve an import that has invalid rows, which are then
 *                           left out; may be omitted (false)
 */
public record ApproveSalesImportRequest(
        @NotNull(message = "version is required")
        Integer version,
        Boolean excludeInvalidRows,
        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {

    public boolean leavesOutInvalidRows() {
        return Boolean.TRUE.equals(excludeInvalidRows);
    }
}
