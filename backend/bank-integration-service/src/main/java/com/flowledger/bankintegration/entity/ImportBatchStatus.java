package com.flowledger.bankintegration.entity;

// Imports are validated and stored in one request, so batches are only ever IMPORTED or FAILED;
// UPLOADED and VALIDATING exist in the schema for a later asynchronous import.
public enum ImportBatchStatus {
    UPLOADED,
    VALIDATING,
    IMPORTED,
    FAILED
}
