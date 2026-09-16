-- Shared durable delivery ledger for cron and delegated-task final output.
CREATE TABLE IF NOT EXISTS delivery_work_items (
    id                  UUID PRIMARY KEY,
    source_type         VARCHAR(64) NOT NULL,
    source_id           VARCHAR(128) NOT NULL,
    profile             VARCHAR(128) NOT NULL,
    user_id             VARCHAR(255),
    parent_session_id   UUID,
    target_kind         VARCHAR(32) NOT NULL,
    platform            VARCHAR(64),
    chat_id             VARCHAR(255),
    thread_id           VARCHAR(255),
    target_hash         VARCHAR(64) NOT NULL,
    payload_text        TEXT NOT NULL,
    payload_hash        VARCHAR(64) NOT NULL,
    state               VARCHAR(32) NOT NULL DEFAULT 'pending',
    attempts            INTEGER NOT NULL DEFAULT 0,
    available_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token         VARCHAR(160),
    claimed_at          TIMESTAMPTZ,
    idempotency_key     VARCHAR(160),
    outbound_message_id VARCHAR(255),
    error_category      VARCHAR(64),
    error_detail        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at        TIMESTAMPTZ,
    dropped_at          TIMESTAMPTZ,
    unknown_at          TIMESTAMPTZ,
    CONSTRAINT uq_delivery_work_items_source_target UNIQUE (source_type, source_id, target_hash),
    CONSTRAINT ck_delivery_work_items_state CHECK (state IN ('pending', 'claimed', 'delivered', 'dropped', 'unknown', 'local_delivered')),
    CONSTRAINT ck_delivery_work_items_target CHECK (
        (target_kind = 'local' AND platform IS NULL AND chat_id IS NULL AND thread_id IS NULL)
        OR (target_kind = 'platform' AND platform IS NOT NULL AND chat_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_delivery_work_items_pending
    ON delivery_work_items (state, available_at, created_at);

CREATE INDEX IF NOT EXISTS idx_delivery_work_items_profile_pending
    ON delivery_work_items (profile, state, available_at, created_at);

CREATE INDEX IF NOT EXISTS idx_delivery_work_items_parent_session
    ON delivery_work_items (parent_session_id, state, created_at);

CREATE TABLE IF NOT EXISTS outbound_message_receipts (
    id                    UUID PRIMARY KEY,
    delivery_work_item_id UUID NOT NULL UNIQUE,
    platform              VARCHAR(64) NOT NULL,
    chat_id               VARCHAR(255) NOT NULL,
    thread_id             VARCHAR(255),
    outbound_message_id   VARCHAR(255) NOT NULL,
    chunk_index           INTEGER NOT NULL DEFAULT 0,
    chunk_count           INTEGER NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_outbound_receipts_delivery_work_item
        FOREIGN KEY (delivery_work_item_id) REFERENCES delivery_work_items(id)
);

CREATE INDEX IF NOT EXISTS idx_outbound_message_receipts_target
    ON outbound_message_receipts (platform, chat_id, thread_id, created_at DESC);
