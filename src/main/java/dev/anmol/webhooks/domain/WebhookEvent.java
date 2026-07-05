package dev.anmol.webhooks.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted record of every webhook received. Acts as both an idempotency
 * ledger (unique constraint on provider + external event id) and an audit
 * trail. Raw payload is stored so failed events can be replayed without
 * asking the provider to re-send.
 */
@Entity
@Table(
    name = "webhook_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_provider_external_id",
        columnNames = {"provider", "external_event_id"}
    )
)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProviderType provider;

    /** Provider's own event id, e.g. Stripe "evt_..." or Razorpay "event_..." */
    @Column(name = "external_event_id", nullable = false, length = 128)
    private String externalEventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Lob
    @Column(name = "raw_payload", nullable = false)
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProcessingStatus status = ProcessingStatus.RECEIVED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookEvent() {
        // JPA
    }

    public WebhookEvent(PaymentProviderType provider, String externalEventId,
                        String eventType, String rawPayload) {
        this.provider = provider;
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
    }

    public void markProcessing() {
        this.status = ProcessingStatus.PROCESSING;
        this.attemptCount++;
    }

    public void markProcessed() {
        this.status = ProcessingStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = ProcessingStatus.FAILED;
        this.lastError = truncate(error);
    }

    public void markDeadLettered(String error) {
        this.status = ProcessingStatus.DEAD_LETTERED;
        this.lastError = truncate(error);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    public Long getId() { return id; }
    public PaymentProviderType getProvider() { return provider; }
    public String getExternalEventId() { return externalEventId; }
    public String getEventType() { return eventType; }
    public String getRawPayload() { return rawPayload; }
    public ProcessingStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
}
