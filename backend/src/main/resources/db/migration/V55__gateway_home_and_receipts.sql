-- V55 (WP-2 / ADR-012): gateway home-channel directory + outbound message
-- receipts + gateway runtime state.
--
-- gateway_home_channels is the persisted authority for bare-platform targets
-- (Hermes HomeChannel parity): /set_home persists here, CronJobService and
-- SendMessageTool resolve bare "telegram" through it instead of the legacy
-- first-allowed-user-id heuristic.
--
-- outbound_message_receipts: V52 created this name with a delivery-work-item
-- -centric shape (delivery_work_item_id NOT NULL UNIQUE, chunk_index) that no
-- Java entity ever mapped — a dead table. The general send-state store needs
-- idempotency_key, session scoping and per-target lookup instead. The old
-- table was never written (no entity, no repository, no SQL writer anywhere
-- in main/), so it is dropped and recreated with the general schema. The
-- delivery lane keeps its own receipt columns on delivery_work_items.
--
-- gateway_runtime_state gives dashboard lifecycle reads a durable view
-- (RUNNING/DRAINING/STOPPED/FAILED) across restarts.

DROP TABLE IF EXISTS outbound_message_receipts;

CREATE TABLE gateway_home_channels (
    platform        VARCHAR(64)  NOT NULL,
    profile         VARCHAR(128) NOT NULL,
    chat_id         VARCHAR(255) NOT NULL,
    thread_id       VARCHAR(255),
    name            VARCHAR(255),
    user_id         VARCHAR(255),
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255),
    CONSTRAINT pk_gateway_home_channels PRIMARY KEY (platform, profile)
);

CREATE TABLE outbound_message_receipts (
    id              UUID         PRIMARY KEY,
    platform        VARCHAR(64)  NOT NULL,
    chat_id         VARCHAR(255) NOT NULL,
    thread_id       VARCHAR(255),
    session_id      UUID,
    user_id         VARCHAR(255),
    direction       VARCHAR(32)  NOT NULL DEFAULT 'outbound',
    message_id      VARCHAR(255) NOT NULL,
    content_hash    VARCHAR(64),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_outbound_receipts_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_outbound_receipts_target_time
    ON outbound_message_receipts (platform, chat_id, thread_id, created_at DESC);

CREATE TABLE gateway_runtime_state (
    profile         VARCHAR(128) PRIMARY KEY,
    state           VARCHAR(32)  NOT NULL,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL
);
