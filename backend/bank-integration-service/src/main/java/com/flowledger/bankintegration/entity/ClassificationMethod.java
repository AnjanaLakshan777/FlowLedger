package com.flowledger.bankintegration.entity;

// Only UNKNOWN (as imported) and MANUAL are used so far; no bank metadata or rules classify yet.
public enum ClassificationMethod {
    BANK_METADATA,
    RULE,
    MANUAL,
    UNKNOWN
}
