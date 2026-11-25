-- Legacy rows retain NULL: their relative order cannot be reconstructed reliably.
-- New inserts receive a monotonic database-assigned tie-breaker.
CREATE SEQUENCE message_insert_order_seq AS BIGINT;
ALTER TABLE messages ADD COLUMN insert_order BIGINT;
ALTER TABLE messages ALTER COLUMN insert_order SET DEFAULT nextval('message_insert_order_seq');
ALTER SEQUENCE message_insert_order_seq OWNED BY messages.insert_order;
