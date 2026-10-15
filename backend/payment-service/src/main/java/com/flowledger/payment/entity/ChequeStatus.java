package com.flowledger.payment.entity;

// The confirmed lifecycle. There is no PAID status: a cheque is never marked paid automatically.
public enum ChequeStatus {
    ISSUED,
    CANCELLED
}
