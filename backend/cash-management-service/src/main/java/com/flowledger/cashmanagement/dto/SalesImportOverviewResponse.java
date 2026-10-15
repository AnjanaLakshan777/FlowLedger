package com.flowledger.cashmanagement.dto;

import java.util.List;

/**
 * The imports the user may see, newest first, and what the user may do. ownImportsOnly is true for
 * an Accountant, who sees only the imports they uploaded.
 */
public record SalesImportOverviewResponse(
        boolean canUpload,
        boolean canApprove,
        boolean ownImportsOnly,
        List<SalesImportBatchResponse> imports
) {
}
