package dev.anmol.webhooks.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async dispatch boundary. Delegates to WebhookProcessor through the Spring
 * proxy so @Retryable semantics are preserved (see WebhookProcessor javadoc).
 *
 * In a production deployment this handoff would typically be an SQS/Kafka
 * publish instead of @Async so processing survives instance crashes; the
 * boundary is designed so that swap touches only this class.
 */
@Service
public class WebhookProcessingService {

    private final WebhookProcessor processor;

    public WebhookProcessingService(WebhookProcessor processor) {
        this.processor = processor;
    }

    @Async
    public void processAsync(Long eventId) {
        processor.process(eventId);
    }
}
