package dev.anmol.webhooks.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.exception.InvalidSignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Razorpay signs webhooks with an "X-Razorpay-Signature" header containing
 * hex(HMAC-SHA256(webhookSecret, rawBody)). Unlike Stripe there is no
 * timestamp scheme, so idempotency (see WebhookIngestionService) is the
 * replay defence.
 *
 * Razorpay payloads don't carry a top-level event id in older API versions;
 * the "x-razorpay-event-id" header is authoritative, but to keep this class
 * transport-agnostic we derive identity from payload contents where present
 * and fall back to a payload hash upstream if needed.
 *
 * Reference: https://razorpay.com/docs/webhooks/validate-test/
 */
@Component
public class RazorpayProvider implements PaymentProvider {

    private final String webhookSecret;
    private final ObjectMapper objectMapper;

    public RazorpayProvider(@Value("${webhooks.razorpay.webhook-secret}") String webhookSecret,
                            ObjectMapper objectMapper) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.RAZORPAY;
    }

    @Override
    public void verifySignature(String rawPayload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("Missing X-Razorpay-Signature header");
        }
        String expected = HmacUtil.hmacSha256Hex(webhookSecret, rawPayload);
        if (!HmacUtil.constantTimeEquals(expected, signatureHeader.trim())) {
            throw new InvalidSignatureException("Razorpay signature mismatch");
        }
    }

    @Override
    public String extractEventId(String rawPayload) {
        // Prefer the entity id inside the payload (e.g. payment id) combined
        // with event type — stable across Razorpay's redeliveries.
        JsonNode root = parse(rawPayload);
        String event = textOrNull(root, "event");
        JsonNode payment = root.at("/payload/payment/entity/id");
        JsonNode order = root.at("/payload/order/entity/id");

        String entityId = !payment.isMissingNode() ? payment.asText()
                : !order.isMissingNode() ? order.asText()
                : null;

        if (event == null || entityId == null || entityId.isBlank()) {
            throw new InvalidSignatureException("Razorpay payload missing event/entity identity");
        }
        return event + ":" + entityId;
    }

    @Override
    public String extractEventType(String rawPayload) {
        String event = textOrNull(parse(rawPayload), "event");
        if (event == null) {
            throw new InvalidSignatureException("Razorpay payload missing 'event'");
        }
        return event;
    }

    private JsonNode parse(String rawPayload) {
        try {
            return objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            throw new InvalidSignatureException("Unparseable Razorpay payload");
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.asText().isBlank()) ? null : v.asText();
    }
}
