package dev.anmol.webhooks.service;

import dev.anmol.webhooks.domain.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deliberately kept as a single seam: everything upstream (verification,
 * idempotency, retries, dead-lettering) is infrastructure that never needs
 * to change when business rules do.
 */
@Component
public class PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    public void handle(WebhookEvent event) {
        switch (event.getEventType()) {
            case "payment_intent.succeeded",      // Stripe
                 "payment.captured"               // Razorpay
                    -> log.info("Payment success: {}", event.getExternalEventId());

            case "payment_intent.payment_failed", // Stripe
                 "payment.failed"                 // Razorpay
                    -> log.info("Payment failure: {}", event.getExternalEventId());

            case "charge.refunded",               // Stripe
                 "refund.processed"               // Razorpay
                    -> log.info("Refund: {}", event.getExternalEventId());

            default -> log.info("Unhandled event type '{}' acknowledged: {}",
                    event.getEventType(), event.getExternalEventId());
        }
    }
}
