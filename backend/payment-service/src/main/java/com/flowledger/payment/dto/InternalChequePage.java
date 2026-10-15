package com.flowledger.payment.dto;

import java.util.List;
import java.util.UUID;

/**
 * One page of the internal cheque scan.
 *
 * @param items       cheques with an ID greater than the requested afterId, in ascending ID order
 * @param nextAfterId the afterId for the next page, or null when this page reached the end of the
 *                    cheques committed when it was read
 */
public record InternalChequePage(List<Item> items, UUID nextAfterId) {

    /**
     * The fields a projection of a cheque needs, named and formatted like the cheque event
     * payload. No payee, notes or audit fields.
     *
     * @param amount  plain decimal string with two decimals, as in the events
     * @param version the cheque's current version, the same value the events carry
     */
    public record Item(
            UUID chequeId,
            UUID companyId,
            UUID bankAccountId,
            String chequeNumber,
            String category,
            String amount,
            String issueDate,
            String expectedClearingDate,
            String status,
            int version
    ) {
    }
}
