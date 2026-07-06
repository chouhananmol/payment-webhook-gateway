package dev.anmol.webhooks.service;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.domain.WebhookEvent;
import dev.anmol.webhooks.provider.PaymentProvider;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The synchronous half of webhook handling. Design goals:
 *
 * 1. Verify the signature BEFORE touching anything else. Unverified input
 *    never reaches the database or business logic.
 * 2. Persist-then-ack: we durably record the event and return 200
 *    immediately. Providers time out in seconds (Stripe ~ 10s); doing
 *    business logic inline is how you end up with provider-side retry storms.
 * 3. Idempotency via a DB unique constraint, not an in-memory check -
 *    survives restarts and works across multiple instances.
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

    private final Map<PaymentProviderType, PaymentProvider> providers;
    private final WebhookEventRepository repository;
    private final WebhookEventWriter writer;
    private final WebhookProcessingService processingService;

    public WebhookIngestionService(List<PaymentProvider> providerList,
                                   WebhookEventRepository repository,
                                   WebhookEventWriter writer,
                                   WebhookProcessingService processingService) {
        this.providers = new EnumMap<>(PaymentProviderType.class);
        providerList.forEach(p -> this.providers.put(p.type(), p));
        this.repository = repository;
        this.writer = writer;
        this.processingService = processingService;
    }

    /**
     * Not @Transactional on purpose. The durable insert runs in its own
     * transaction inside {@link WebhookEventWriter} so a losing race throws a
     * DataIntegrityViolationException that we can catch here cleanly, rather
     * than poisoning an enclosing transaction into an UnexpectedRollback.
     *
     * @return the persisted event, or the previously stored duplicate.
     */
    public IngestionResult ingest(PaymentProviderType providerType,
                                  String rawPayload,
                                  String signatureHeader) {
        PaymentProvider provider = providers.get(providerType);
        if (provider == null) {
            throw new IllegalArgumentException("No provider registered for " + providerType);
        }

        // Step 1: authenticate the payload.
        provider.verifySignature(rawPayload, signatureHeader);

        String eventId = provider.extractEventId(rawPayload);
        String eventType = provider.extractEventType(rawPayload);

        // Step 2: cheap duplicate pre-check (fast path for provider retries).
        if (repository.existsByProviderAndExternalEventId(providerType, eventId)) {
            log.info("Duplicate webhook ignored: provider={} eventId={}", providerType, eventId);
            return IngestionResult.duplicate(eventId);
        }

        // Step 3: durable insert. The unique constraint is the real guarantee —
        // two concurrent deliveries of the same event race here and exactly
        // one wins.
        WebhookEvent event = new WebhookEvent(providerType, eventId, eventType, rawPayload);
        try {
            event = writer.insertNew(event);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent duplicate webhook ignored: provider={} eventId={}",
                    providerType, eventId);
            return IngestionResult.duplicate(eventId);
        }

        // Step 4: hand off to async processing and ack the provider.
        processingService.processAsync(event.getId());
        return IngestionResult.accepted(eventId);
    }

    public record IngestionResult(String eventId, boolean duplicate) {
        static IngestionResult accepted(String eventId) {
            return new IngestionResult(eventId, false);
        }
        static IngestionResult duplicate(String eventId) {
            return new IngestionResult(eventId, true);
        }
    }
}
