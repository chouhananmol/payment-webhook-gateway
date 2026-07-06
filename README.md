# Payment Webhook Gateway

![CI](https://github.com/chouhananmol/payment-webhook-gateway/actions/workflows/ci.yml/badge.svg)

Production-grade webhook ingestion for **Stripe** and **Razorpay** in Spring Boot. Handles the four things payment webhook integrations most commonly get wrong:

| Problem | How this handles it |
|---|---|
| **Forged webhooks** | HMAC-SHA256 verification per provider, constant-time comparison, replay-window check (Stripe) |
| **Duplicate deliveries** | DB-backed idempotency ledger with a unique constraint that survives restarts and works across instances |
| **Provider timeouts and retry storms** | Persist-then-ack: 200 returned in milliseconds, business logic runs async |
| **Lost events on failure** | Exponential-backoff retries, then dead-letter status, then a replay endpoint (raw payload is stored, so no provider re-send needed) |

## Quick start

```bash
docker compose up --build     # app + PostgreSQL, Flyway-managed schema
# or, zero-dependency:
mvn spring-boot:run           # H2 in-memory
mvn test                      # unit + end-to-end integration tests
```

Then import `postman_collection.json`. The pre-request scripts compute valid HMAC signatures automatically, so you can fire signed Stripe and Razorpay events, watch duplicates get suppressed, and see forged signatures bounce with 401.

## Architecture

```
POST /webhooks/{stripe|razorpay}
        │
        ▼
┌─ WebhookController ────────── raw body, never deserialized before verification
│
├─ WebhookIngestionService ──── 1. verify signature (reject to 401)
│                               2. idempotency check (duplicate to 200)
│                               3. durable insert (unique constraint is race-safe)
│                               4. ack provider with 200
│
└─ WebhookProcessingService ─── async handoff (swap for SQS/Kafka is one class)
        │
        ▼
   WebhookProcessor ─────────── @Retryable: 4 attempts, backoff 1s/2s/4s
        │                       exhausted, then DEAD_LETTERED (+ metrics)
        ▼
   PaymentEventHandler ──────── single seam for business logic
```

Adding a provider (PayPal, Cashfree, PayU) is one class implementing `PaymentProvider`.

## Design decisions worth reading

- **Raw body in the controller.** Deserializing to a DTO and re-serializing changes whitespace and field order, so signature verification then fails intermittently. This is the most common webhook bug in production.
- **Constant-time signature comparison** (`MessageDigest.isEqual`). `String.equals()` early-exits and leaks timing information an attacker can exploit.
- **Unique constraint over in-memory dedup.** In-memory sets do not survive restarts or work across load-balanced instances. The constraint also correctly resolves two concurrent deliveries of the same event racing, so exactly one insert wins.
- **The insert runs in its own transaction.** When a delivery loses the race, the unique constraint throws `DataIntegrityViolationException`. Catching that inside the caller's own transaction would not work: Spring has already marked the transaction rollback-only, so the later commit throws `UnexpectedRollbackException`. Isolating the insert in a dedicated transactional bean lets the violation cross a boundary and roll back cleanly, so the caller catches it and returns a duplicate.
- **Duplicates return 200, not 409.** Providers treat any non-2xx as "retry me", so erroring on duplicates creates an infinite retry loop.
- **Retryable logic isolated in `WebhookProcessor`.** `@Retryable` is proxy-based, so self-invocation from the same class silently bypasses the proxy and retries never fire. A dedicated bean makes the proxy path structural, not accidental.
- **Flyway owns the schema** (`ddl-auto: validate` in the Postgres profile). Hibernate auto-DDL in production is how columns silently drift.

## Observability

`webhooks.processed` and `webhooks.dead_lettered` counters plus a `webhooks.processing.latency` timer (with a percentile histogram), all on `/actuator/metrics`. `/actuator/prometheus` exposes them for scraping. `/actuator/health` for probes. Every processing log line carries the event id, external event id and provider in the MDC, so one event is greppable end to end through the async pipeline.

## Operations

```bash
GET  /admin/webhooks/dead-letter     # inspect failed events
POST /admin/webhooks/{id}/replay     # replay from stored payload
```

These require an `X-Admin-Api-Key` header matching `ADMIN_API_KEY`. The key is compared in constant time, and requests without it get 401 before reaching any handler. In production these also sit on an internal network.

## Verified behavior (test suite)

- Valid Stripe and Razorpay signatures accepted. Tampered payloads and forged headers rejected with 401 and **nothing persisted**
- Stale Stripe timestamps rejected (replay attack)
- Redelivered events return 200 with `duplicate: true`, exactly one row, processed exactly once
- 16 concurrent deliveries of the same event race on the unique constraint: exactly one row persisted, one caller accepted, the rest told duplicate
- Full flow verified against real PostgreSQL in a container (Testcontainers), including the Flyway migration and Hibernate schema validation
- Admin endpoints reject requests without a valid API key (401)
- Full async pipeline completes end-to-end (MockMvc + Awaitility)

## Stack

Java 17, Spring Boot 3.3, Spring Data JPA, Spring Retry, Flyway, PostgreSQL / H2, Micrometer (Prometheus), Docker, GitHub Actions, JUnit 5, Testcontainers, JaCoCo

---

*Built by a backend engineer working on payments infrastructure (Java/Spring Boot/AWS SQS) at a fintech processing live transaction volume.*