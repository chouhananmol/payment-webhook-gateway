package dev.anmol.webhooks.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.exception.InvalidSignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Stripe signs webhooks with a "Stripe-Signature" header of the form:
 *
 *   t=1712345678,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
 *
 * The signed payload is "{timestamp}.{rawBody}" HMAC'd with the endpoint
 * secret. We also reject stale timestamps to block replay attacks.
 *
 * Reference: https://docs.stripe.com/webhooks#verify-manually
 */
@Component
public class StripeProvider implements PaymentProvider {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final String endpointSecret;
    private final ObjectMapper objectMapper;

    public StripeProvider(@Value("${webhooks.stripe.endpoint-secret}") String endpointSecret,
                          ObjectMapper objectMapper) {
        this.endpointSecret = endpointSecret;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.STRIPE;
    }

    @Override
    public void verifySignature(String rawPayload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("Missing Stripe-Signature header");
        }

        long timestamp = -1;
        String v1Signature = null;

        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "t" -> timestamp = parseTimestamp(kv[1].trim());
                case "v1" -> v1Signature = kv[1].trim();
                default -> { /* ignore v0 and unknown schemes */ }
            }
        }

        if (timestamp < 0 || v1Signature == null) {
            throw new InvalidSignatureException("Malformed Stripe-Signature header");
        }

        Instant signedAt = Instant.ofEpochSecond(timestamp);
        if (Duration.between(signedAt, Instant.now()).abs().compareTo(TOLERANCE) > 0) {
            throw new InvalidSignatureException(
                    "Stripe signature timestamp outside tolerance window (possible replay)");
        }

        String signedPayload = timestamp + "." + rawPayload;
        String expected = HmacUtil.hmacSha256Hex(endpointSecret, signedPayload);

        if (!HmacUtil.constantTimeEquals(expected, v1Signature)) {
            throw new InvalidSignatureException("Stripe signature mismatch");
        }
    }

    @Override
    public String extractEventId(String rawPayload) {
        return readField(rawPayload, "id");
    }

    @Override
    public String extractEventType(String rawPayload) {
        return readField(rawPayload, "type");
    }

    private long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("Invalid timestamp in Stripe-Signature header");
        }
    }

    private String readField(String rawPayload, String field) {
        try {
            JsonNode node = objectMapper.readTree(rawPayload);
            JsonNode value = node.get(field);
            if (value == null || value.asText().isBlank()) {
                throw new InvalidSignatureException("Stripe payload missing '" + field + "'");
            }
            return value.asText();
        } catch (InvalidSignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidSignatureException("Unparseable Stripe payload");
        }
    }
}
