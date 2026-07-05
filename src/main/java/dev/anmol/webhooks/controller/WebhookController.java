package dev.anmol.webhooks.controller;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.service.WebhookIngestionService;
import dev.anmol.webhooks.service.WebhookIngestionService.IngestionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Raw-body endpoints. The body MUST be read as a raw String — deserializing
 * to a DTO and re-serializing changes whitespace/field order and breaks
 * signature verification. This is the #1 webhook bug in the wild.
 *
 * Always returns 200 for duplicates: providers treat non-2xx as "retry me".
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookIngestionService ingestionService;

    public WebhookController(WebhookIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, Object>> stripe(
            @RequestBody String rawPayload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        IngestionResult result = ingestionService.ingest(
                PaymentProviderType.STRIPE, rawPayload, signature);
        return ok(result);
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Map<String, Object>> razorpay(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        IngestionResult result = ingestionService.ingest(
                PaymentProviderType.RAZORPAY, rawPayload, signature);
        return ok(result);
    }

    private ResponseEntity<Map<String, Object>> ok(IngestionResult result) {
        return ResponseEntity.ok(Map.of(
                "eventId", result.eventId(),
                "duplicate", result.duplicate()));
    }
}
