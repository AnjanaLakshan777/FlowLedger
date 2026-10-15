package com.flowledger.payment.dto;

import java.util.List;
import java.util.UUID;

/**
 * Cheque counts per company, all computed by one SQL statement, so they are consistent with each
 * other at one moment. They are not tied to any scan page.
 *
 * @param chequeCount total cheques of all listed companies
 * @param companies   one entry per company, ascending by company ID
 */
public record ChequeSummaryResponse(long chequeCount, List<Company> companies) {

    /**
     * @param versionSum sum of the current versions of the company's cheques. Versions only grow
     *                   and cheques are never deleted, so a copy whose cheques are a subset of
     *                   these, each at a version no higher than here, is complete and current
     *                   exactly when its count and version sum both match.
     */
    public record Company(UUID companyId, long chequeCount, long issuedCount, long cancelledCount, long versionSum) {
    }
}
