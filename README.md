# Payment Webhook Gateway

![CI](https://github.com/chouhananmol/payment-webhook-gateway/actions/workflows/ci.yml/badge.svg)

Production-grade webhook ingestion for **Stripe** and **Razorpay** in Spring Boot. Handles the four things payment webhook integrations most commonly get wrong:

| Problem | How this handles it |
|---|---|
| **Forged webhooks** | HMAC-SHA256 verification per provider, constant-time comparison, replay-window check (Stripe) |
| **Duplicate deliveries** | DB-backed idempotency ledger with a unique constraint — survives restarts, works across instances |
| **Provider timeouts / retry storms** | Persist-then-ack: 200 returned in milliseconds, business logic runs async |
| **Lost events on failure** | Exponential-backoff retries → dead-letter status → replay endpoint (raw payload stored, no provider re-send needed) |

## Quick start

```bash
docker compose up --build     # app + PostgreSQL, Flyway-managed schema
# or, zero-dependency:
mvn spring-boot:run           # H2 in-memory
mvn test                      # unit + end-to-end integration tests
```

Then import `postman_collection.json` — the pre-request scripts compute valid HMAC signatures automatically, so you can fire signed Stripe/Razorpay events, watch duplicates get suppressed, and see forged signatures bounce with 401.

## Architecture

```
POST /webhooks/{stripe|razorpay}
        │
        ▼
┌─ WebhookController ────────── raw body, never deserialized before verification
│
├─ WebhookIngestionService ──── 1. verify signature (reject → 401)
│                               2. idempotency check (duplicate → 200)
│                               3. durable insert (unique constraint = race-safe)
│                               4. ack provider with 200
│
└─ WebhookProcessingService ─── async handoff (swap for SQS/Kafka = one class)
        │
        ▼
   WebhookProcessor ─────────── @Retryable: 4 attempts, backoff 1s/2s/4s
        │                       exhausted → DEAD_LETTERED (+ metrics)
        ▼
   PaymentEventHandler ──────── single seam for business logic
```

Adding a provider (PayPal, Cashfree, PayU) = one class implementing `PaymentProvider`.

## Design decisions worth reading

- **Raw body in the controller.** Deserializing to a DTO and re-serializing changes whitespace and field order — signature verification then fails intermittently. The most common webhook bug in production.
- **Constant-time signature comparison** (`MessageDigest.isEqual`). `String.equals()` early-exits and leaks timing information an attacker can exploit.
- **Unique constraint over in-memory dedup.** In-memory sets don't survive restarts or work across load-balanced instances. The constraint also correctly resolves two concurrent deliveries of the same event racing — exactly one insert wins.
- **Duplicates return 200, not 409.** Providers treat any non-2xx as "retry me"; erroring on duplicates creates an infinite retry loop.
- **Retryable logic isolated in `WebhookProcessor`.** `@Retryable` is proxy-based; self-invocation from the same class silently bypasses the proxy and retries never fire. A dedicated bean makes the proxy path structural, not accidental.
- **Flyway owns the schema** (`ddl-auto: validate` in the Postgres profile). Hibernate auto-DDL in production is how columns silently drift.

## Observability

`/actuator/metrics/webhooks.processed` and `webhooks.dead_lettered` counters; `/actuator/health` for probes.

## Operations

```bash
GET  /admin/webhooks/dead-letter     # inspect failed events
POST /admin/webhooks/{id}/replay     # replay from stored payload
```

(In production these sit behind auth on an internal network.)

## Verified behavior (test suite)

- Valid Stripe/Razorpay signatures accepted; tampered payloads and forged headers rejected with 401 and **nothing persisted**
- Stale Stripe timestamps rejected (replay attack)
- Redelivered events return 200 + `duplicate: true`, exactly one row, processed exactly once
- Full async pipeline completes end-to-end (MockMvc + Awaitility)

## Stack

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Retry · Flyway · PostgreSQL / H2 · Micrometer · Docker · GitHub Actions · JUnit 5

---

*Built by a backend engineer working on payments infrastructure (Java/Spring Boot/AWS SQS) at a fintech processing live transaction volume.*
