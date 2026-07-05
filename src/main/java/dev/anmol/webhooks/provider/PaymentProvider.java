package dev.anmol.webhooks.provider;

import dev.anmol.webhooks.domain.PaymentProviderType;

/**
 * Strategy interface. Each payment provider knows how to verify its own
 * signature scheme and extract event identity from its payload format.
 * Adding a new provider (PayPal, Cashfree, PayU...) means adding one class.
 */
public interface PaymentProvider {

    PaymentProviderType type();

    /**
     * Verify the webhook signature against the raw request body.
     *
     * @throws dev.anmol.webhooks.exception.InvalidSignatureException if invalid
     */
    void verifySignature(String rawPayload, String signatureHeader);

    /** Extract the provider's unique event id (used for idempotency). */
    String extractEventId(String rawPayload);

    /** Extract the event type, e.g. "payment_intent.succeeded" or "payment.captured". */
    String extractEventType(String rawPayload);
}
