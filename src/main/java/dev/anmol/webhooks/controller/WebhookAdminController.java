package dev.anmol.webhooks.controller;

import dev.anmol.webhooks.domain.ProcessingStatus;
import dev.anmol.webhooks.domain.WebhookEvent;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import dev.anmol.webhooks.service.WebhookProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Operational endpoints: inspect the dead-letter queue and replay events.
 * In production these sit behind auth (Spring Security) and are exposed
 * only on an internal network — omitted here to keep the demo focused.
 */
@RestController
@RequestMapping("/admin/webhooks")
public class WebhookAdminController {

    private final WebhookEventRepository repository;
    private final WebhookProcessingService processingService;

    public WebhookAdminController(WebhookEventRepository repository,
                                  WebhookProcessingService processingService) {
        this.repository = repository;
        this.processingService = processingService;
    }

    @GetMapping("/dead-letter")
    public List<Map<String, Object>> deadLetterQueue() {
        return repository.findTop50ByStatusOrderByReceivedAtAsc(ProcessingStatus.DEAD_LETTERED)
                .stream()
                .map(this::summarize)
                .toList();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable Long id) {
        return repository.findById(id)
                .map(event -> {
                    processingService.processAsync(event.getId());
                    return ResponseEntity.ok(summarize(event));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> summarize(WebhookEvent e) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("provider", e.getProvider());
        map.put("externalEventId", e.getExternalEventId());
        map.put("eventType", e.getEventType());
        map.put("status", e.getStatus());
        map.put("attemptCount", e.getAttemptCount());
        map.put("lastError", e.getLastError());
        map.put("receivedAt", e.getReceivedAt());
        return map;
    }
}
