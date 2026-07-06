package dev.anmol.webhooks.service;

import dev.anmol.webhooks.domain.WebhookEvent;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the durable insert in its own transaction.
 *
 * Why a separate bean: when two deliveries of the same event race, one insert
 * hits the unique constraint and throws DataIntegrityViolationException. If
 * that were caught inside the caller's own transaction, Spring would have
 * already marked the transaction rollback-only and the later commit would
 * throw UnexpectedRollbackException. By letting the exception propagate out of
 * this dedicated transactional boundary, the failed insert rolls back cleanly
 * and the caller can catch the exception and treat it as a duplicate.
 */
@Service
public class WebhookEventWriter {

    private final WebhookEventRepository repository;

    public WebhookEventWriter(WebhookEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts a new event in its own transaction. Throws
     * {@link org.springframework.dao.DataIntegrityViolationException} if the
     * provider + external event id already exists.
     */
    @Transactional
    public WebhookEvent insertNew(WebhookEvent event) {
        return repository.saveAndFlush(event);
    }
}
