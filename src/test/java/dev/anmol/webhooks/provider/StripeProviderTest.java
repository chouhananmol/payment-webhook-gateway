package dev.anmol.webhooks.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.anmol.webhooks.exception.InvalidSignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class StripeProviderTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String PAYLOAD =
            "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\"}";

    private StripeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new StripeProvider(SECRET, new ObjectMapper());
    }

    @Test
    void acceptsValidSignature() {
        long ts = Instant.now().getEpochSecond();
        String header = "t=" + ts + ",v1=" + sign(ts + "." + PAYLOAD);
        assertDoesNotThrow(() -> provider.verifySignature(PAYLOAD, header));
    }

    @Test
    void rejectsTamperedPayload() {
        long ts = Instant.now().getEpochSecond();
        String header = "t=" + ts + ",v1=" + sign(ts + "." + PAYLOAD);
        String tampered = PAYLOAD.replace("evt_123", "evt_999");
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(tampered, header));
    }

    @Test
    void rejectsStaleTimestamp_replayAttack() {
        long stale = Instant.now().minusSeconds(3600).getEpochSecond();
        String header = "t=" + stale + ",v1=" + sign(stale + "." + PAYLOAD);
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(PAYLOAD, header));
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(PAYLOAD, null));
    }

    @Test
    void rejectsMalformedHeader() {
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(PAYLOAD, "garbage"));
    }

    @Test
    void extractsEventIdAndType() {
        assertEquals("evt_123", provider.extractEventId(PAYLOAD));
        assertEquals("payment_intent.succeeded", provider.extractEventType(PAYLOAD));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
