package dev.anmol.webhooks;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.domain.ProcessingStatus;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Runs the full flow against a real PostgreSQL in a container under the
 * postgres profile. This exercises what the H2 profile cannot: the production
 * database, the Flyway migration on that database, and Hibernate schema
 * validation (ddl-auto: validate) against the migrated schema. If the entity
 * mapping and the migration ever drift apart, this test fails at startup.
 *
 * Requires a Docker daemon (present on CI runners).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresFlowIntegrationTest {

    private static final String STRIPE_SECRET = "whsec_test_secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookEventRepository repository;

    @Test
    void signedStripeEvent_persistsAndProcessesAgainstRealPostgres() throws Exception {
        String payload = "{\"id\":\"evt_pg_1\",\"type\":\"payment_intent.succeeded\"}";

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", stripeHeader(payload))
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        assertEquals(1, repository.count());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var event = repository.findByProviderAndExternalEventId(
                    PaymentProviderType.STRIPE, "evt_pg_1").orElseThrow();
            assertEquals(ProcessingStatus.PROCESSED, event.getStatus());
        });
    }

    private String stripeHeader(String payload) {
        long ts = Instant.now().getEpochSecond();
        return "t=" + ts + ",v1=" + hmac(ts + "." + payload);
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(STRIPE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
