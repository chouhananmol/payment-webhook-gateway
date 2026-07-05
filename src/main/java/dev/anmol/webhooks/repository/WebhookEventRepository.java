package dev.anmol.webhooks.repository;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.domain.ProcessingStatus;
import dev.anmol.webhooks.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByProviderAndExternalEventId(
            PaymentProviderType provider, String externalEventId);

    boolean existsByProviderAndExternalEventId(
            PaymentProviderType provider, String externalEventId);

    List<WebhookEvent> findTop50ByStatusOrderByReceivedAtAsc(ProcessingStatus status);
}
