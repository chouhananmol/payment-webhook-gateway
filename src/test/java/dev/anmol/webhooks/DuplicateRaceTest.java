package dev.anmol.webhooks;

import dev.anmol.webhooks.domain.PaymentProviderType;
import dev.anmol.webhooks.repository.WebhookEventRepository;
import dev.anmol.webhooks.service.WebhookIngestionService;
import dev.anmol.webhooks.service.WebhookIngestionService.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the claim in the README: when the same event is delivered many times
 * at once, the unique constraint lets exactly one insert win and every other
 * caller is told it is a duplicate. This is the race that in-memory dedup
 * silently gets wrong.
 */
@SpringBootTest
class DuplicateRaceTest {

    private static final String STRIPE_SECRET = "whsec_test_secret";
    private static final int CONCURRENCY = 16;

    @Autowired
    private WebhookIngestionService ingestionService;

    @Autowired
    private WebhookEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void concurrentDeliveriesOfSameEvent_insertExactlyOneRow() throws Exception {
        String payload = "{\"id\":\"evt_race_1\",\"type\":\"payment_intent.succeeded\"}";
        String header = stripeHeader(payload);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    IngestionResult result =
                            ingestionService.ingest(PaymentProviderType.STRIPE, payload, header);
                    if (result.duplicate()) {
                        duplicates.incrementAndGet();
                    } else {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // release every thread at once
            pool.shutdown();
            //noinspection ResultOfMethodCallIgnored
            pool.awaitTermination(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, repository.count(), "exactly one row must be persisted");
        assertEquals(1, accepted.get(), "exactly one caller may be told it was accepted");
        assertEquals(CONCURRENCY - 1, duplicates.get(), "every other caller must see a duplicate");
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
