package dev.anmol.webhooks.domain;

/**
 * Lifecycle of a webhook event.
 *
 * RECEIVED       -> persisted, waiting for async processing
 * PROCESSING     -> a worker has picked it up
 * PROCESSED      -> business logic completed successfully
 * FAILED         -> transient failure; eligible for retry
 * DEAD_LETTERED  -> retries exhausted; needs manual replay
 */
public enum ProcessingStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    FAILED,
    DEAD_LETTERED
}
