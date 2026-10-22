package com.flowledger.payment.entity;

// The confirmed lifecycle. There is no PAID status: a cheque is never marked paid automatically.
// BOUNCED: the bank returned the cheque unpaid, recorded by a user.
public enum ChequeStatus {
    ISSUED,
    CANCELLED,
    BOUNCED
}
