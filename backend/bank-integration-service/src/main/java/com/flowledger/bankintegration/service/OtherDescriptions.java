package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.exception.InvalidRequestException;

// An OTHER transaction must say what it was, so it reads as "Other - <description>".
final class OtherDescriptions {

    private OtherDescriptions() {
    }

    // The description to store: trimmed for OTHER, where it is required, and null otherwise.
    static String require(TransactionCategory category, String description) {
        if (category != TransactionCategory.OTHER) {
            return null;
        }
        if (description == null || description.isBlank()) {
            throw new InvalidRequestException("otherDescription is required when the category is OTHER.");
        }
        return description.strip();
    }
}
