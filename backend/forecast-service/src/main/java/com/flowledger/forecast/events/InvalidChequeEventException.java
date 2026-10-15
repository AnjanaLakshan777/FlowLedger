package com.flowledger.forecast.events;

/**
 * A message that can never be applied: unsupported, malformed, or for another company's cheque.
 * It goes to the dead-letter queue without retries. The message names fields, never their values,
 * so it is safe to log.
 */
public class InvalidChequeEventException extends RuntimeException {

    public InvalidChequeEventException(String message) {
        super(message);
    }
}
