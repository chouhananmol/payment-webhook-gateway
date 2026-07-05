package dev.anmol.webhooks.service;

import dev.anmol.webhooks.domain.ProcessingStatus;
import dev.anmol.webhooks.domain.WebhookEvent;
import dev.anmol.webhooks.exception.WebhookProcessingException;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retryable processing unit, isolated in its own bean.
 *
 * Why a separate class: @Retryable and @Transactional are proxy-based.
 * If the same class called its own retryable method (self-invocation),
 * the call would bypass the proxy and retries would silently never fire -
 * a classic Spring AOP pitfall. Keepng the retryable entry point in a
 * dedicated bean guarantees every call goes through the proxy.
 */
@Service
public class WebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

    private final WebhookEventRepository repository;
    private final PaymentEventHandler eventHandler;
    private final Counter processedCounter;
    private final Counter deadLetterCounter;

    public WebhookProcessor(WebhookEventRepository repository,
                            PaymentEventHandler eventHandler,
                            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.eventHandler = eventHandler;
        this.processedCounter = meterRegistry.counter("webhooks.processed");
        this.deadLetterCounter = meterRegistry.counter("webhooks.dead_lettered");
    }

    @Retryable(
            retryFor = WebhookProcessingException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    @Transactional
    public void process(Long eventId) {
        WebhookEvent event = repository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Event not found: " + eventId));

        if (event.getStatus() == ProcessingStatus.PROCESSED) {
            return; // Another worker finished it.
        }

        event.markProcessing();
        try {
            eventHandler.handle(event);
            event.markProcessed();
            processedCounter.increment();
            log.info("Processed webhook: provider={} eventId={} type={} attempts={}",
                    event.getProvider(), event.getExternalEventId(),
                    event.getEventType(), event.getAttemptCount());
        } catch (Exception e) {
            event.markFailed(e.getMessage());
            repository.saveAndFlush(event);
            throw new WebhookProcessingException(
                    "Attempt " + event.getAttemptCount() + " failed for event " + eventId, e);
        }
    }

    @Recover
    @Transactional
    public void deadLetter(WebhookProcessingException e, Long eventId) {
        repository.findById(eventId).ifPresent(event -> {
            event.markDeadLettered(e.getMessage());
            repository.save(event);
            deadLetterCounter.increment();
            log.error("Webhook dead-lettered after {} attempts: provider={} eventId={}",
                    event.getAttemptCount(), event.getProvider(), event.getExternalEventId());
        });
    }
}
