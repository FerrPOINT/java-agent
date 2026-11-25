-- Legacy order is unknown; new messages receive a stable sequence value.
CREATE SEQUENCE message_insert_order_seq AS BIGINT;
ALTER TABLE messages ADD COLUMN insert_order BIGINT;
ALTER TABLE messages ALTER COLUMN insert_order SET DEFAULT NEXT VALUE FOR message_insert_order_seq;
