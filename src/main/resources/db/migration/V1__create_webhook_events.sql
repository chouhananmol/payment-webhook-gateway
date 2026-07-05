CREATE TABLE webhook_events (
    id                BIGSERIAL PRIMARY KEY,
    provider          VARCHAR(20)   NOT NULL,
    external_event_id VARCHAR(128)  NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    raw_payload       TEXT          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    attempt_count     INT           NOT NULL DEFAULT 0,
    last_error        VARCHAR(2000),
    received_at       TIMESTAMP   NOT NULL DEFAULT now(),
    processed_at      TIMESTAMP,
    CONSTRAINT uq_provider_external_id UNIQUE (provider, external_event_id)
);

-- Worker poll path: fetch oldest unprocessed events
CREATE INDEX idx_webhook_events_status_received
    ON webhook_events (status, received_at);
