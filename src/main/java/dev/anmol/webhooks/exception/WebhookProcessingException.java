package dev.anmol.webhooks.exception;

/** Transient processing failure. Triggers retry with exponential backoff. */
public class WebhookProcessingException extends RuntimeException {
    public WebhookProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
