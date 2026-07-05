package dev.anmol.webhooks.exception;

/** Thrown when a webhook's signature fails verification. Always results in HTTP 401. */
public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException(String message) {
        super(message);
    }
}
