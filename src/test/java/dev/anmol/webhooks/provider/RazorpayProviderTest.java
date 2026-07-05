package dev.anmol.webhooks.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.anmol.webhooks.exception.InvalidSignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayProviderTest {

    private static final String SECRET = "rzp_test_secret";
    private static final String PAYLOAD = """
            {"event":"payment.captured",
             "payload":{"payment":{"entity":{"id":"pay_ABC123"}}}}""";

    private RazorpayProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RazorpayProvider(SECRET, new ObjectMapper());
    }

    @Test
    void acceptsValidSignature() {
        assertDoesNotThrow(() -> provider.verifySignature(PAYLOAD, sign(PAYLOAD)));
    }

    @Test
    void rejectsTamperedPayload() {
        String signature = sign(PAYLOAD);
        String tampered = PAYLOAD.replace("pay_ABC123", "pay_XYZ999");
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(tampered, signature));
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(InvalidSignatureException.class,
                () -> provider.verifySignature(PAYLOAD, ""));
    }

    @Test
    void derivesStableEventIdentity() {
        assertEquals("payment.captured:pay_ABC123", provider.extractEventId(PAYLOAD));
        assertEquals("payment.captured", provider.extractEventType(PAYLOAD));
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
