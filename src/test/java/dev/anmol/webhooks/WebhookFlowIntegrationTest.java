package dev.anmol.webhooks;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.domain.ProcessingStatus;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test through the HTTP layer: signed request in, async
 * processing out, duplicates suppressed, forgeries rejected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookFlowIntegrationTest {

    private static final String STRIPE_SECRET = "whsec_test_secret";
    private static final String RAZORPAY_SECRET = "rzp_test_secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void stripeEvent_isVerifiedPersistedAndProcessedOnce() throws Exception {
        String payload = "{\"id\":\"evt_int_1\",\"type\":\"payment_intent.succeeded\"}";
        String header = stripeHeader(payload);

        // First delivery: accepted
        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        // Redelivery of the same event: 200, flagged duplicate, no second row
        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertEquals(1, repository.count());

        // Async worker completes
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var event = repository.findByProviderAndExternalEventId(
                    PaymentProviderType.STRIPE, "evt_int_1").orElseThrow();
            assertEquals(ProcessingStatus.PROCESSED, event.getStatus());
        });
    }

    @Test
    void razorpayEvent_isVerifiedAndProcessed() throws Exception {
        String payload = "{\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_int_1\"}}}}";

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", hmac(RAZORPAY_SECRET, payload))
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var event = repository.findByProviderAndExternalEventId(
                    PaymentProviderType.RAZORPAY, "payment.captured:pay_int_1").orElseThrow();
            assertEquals(ProcessingStatus.PROCESSED, event.getStatus());
        });
    }

    @Test
    void forgedSignature_isRejectedWith401_andNothingIsPersisted() throws Exception {
        String payload = "{\"id\":\"evt_forged\",\"type\":\"payment_intent.succeeded\"}";

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=123,v1=deadbeef")
                        .content(payload))
                .andExpect(status().isUnauthorized());

        assertEquals(0, repository.count());
    }

    private String stripeHeader(String payload) {
        long ts = Instant.now().getEpochSecond();
        return "t=" + ts + ",v1=" + hmac(STRIPE_SECRET, ts + "." + payload);
    }

    private String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
