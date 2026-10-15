-- UTC DATETIME(6) throughout. Application time comes from an injectable Clock.
CREATE TABLE venue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    city VARCHAR(100) NOT NULL,
    address VARCHAR(500) NOT NULL
);
CREATE TABLE hall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    layout_version INT NOT NULL,
    FOREIGN KEY (venue_id) REFERENCES venue(id),
    CHECK (layout_version > 0)
);
CREATE TABLE hall_seat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id BIGINT NOT NULL,
    row_no VARCHAR(20) NOT NULL,
    seat_no VARCHAR(20) NOT NULL,
    position INT NOT NULL,
    block_id VARCHAR(40) NOT NULL,
    price_zone VARCHAR(40) NOT NULL,
    x DECIMAL(10,2) NOT NULL,
    y DECIMAL(10,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (hall_id) REFERENCES hall(id),
    UNIQUE (hall_id,row_no,seat_no),
    UNIQUE (hall_id,row_no,position),
    CHECK (position > 0)
);
CREATE TABLE show_catalog (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    duration_minutes INT NOT NULL,
    CHECK (duration_minutes > 0)
);
CREATE TABLE show_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    show_id BIGINT NOT NULL,
    hall_id BIGINT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    sales_start_at DATETIME(6) NOT NULL,
    sales_end_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    layout_version INT NOT NULL,
    FOREIGN KEY (show_id) REFERENCES show_catalog(id),
    FOREIGN KEY (hall_id) REFERENCES hall(id),
    CHECK (status IN ('DRAFT','ON_SALE','CLOSED','CANCELLED')),
    CHECK (sales_start_at < sales_end_at AND sales_end_at <= starts_at),
    CHECK (layout_version > 0)
);
CREATE INDEX idx_session_show_time ON show_session(show_id,starts_at);
CREATE TABLE user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    CHECK (role IN ('USER','ADMIN'))
);
CREATE TABLE ticket_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    selection_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    expire_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id),
    FOREIGN KEY (session_id) REFERENCES show_session(id),
    CONSTRAINT uq_order_user_request UNIQUE (user_id,request_id),
    CONSTRAINT uq_order_session UNIQUE (id,session_id),
    CHECK (selection_mode IN ('EXPLICIT','ADJACENT')),
    CHECK (status IN ('UNPAID','PAID','CANCELLED','REFUNDING','REFUNDED')),
    CHECK (total_amount >= 0),
    CHECK (expire_at >= created_at)
);
CREATE INDEX idx_order_expiry ON ticket_order(status,expire_at,id);
CREATE INDEX idx_order_user_created ON ticket_order(user_id,created_at);
CREATE TABLE session_seat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    row_no VARCHAR(20) NOT NULL,
    seat_no VARCHAR(20) NOT NULL,
    position INT NOT NULL,
    block_id VARCHAR(40) NOT NULL,
    price_zone VARCHAR(40) NOT NULL,
    x DECIMAL(10,2) NOT NULL,
    y DECIMAL(10,2) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_order_id BIGINT,
    hold_expires_at DATETIME(6),
    FOREIGN KEY (session_id) REFERENCES show_session(id),
    FOREIGN KEY (seat_id) REFERENCES hall_seat(id),
    FOREIGN KEY (owner_order_id,session_id) REFERENCES ticket_order(id,session_id),
    CONSTRAINT uq_session_seat UNIQUE (session_id,seat_id),
    UNIQUE (session_id,row_no,position),
    CHECK (price >= 0 AND position > 0),
    CHECK ((status IN ('AVAILABLE','DISABLED') AND owner_order_id IS NULL AND hold_expires_at IS NULL)
        OR (status='HELD' AND owner_order_id IS NOT NULL AND hold_expires_at IS NOT NULL)
        OR (status='SOLD' AND owner_order_id IS NOT NULL AND hold_expires_at IS NULL))
);
CREATE INDEX idx_seat_candidates ON session_seat(session_id,status,row_no,block_id,position);
CREATE INDEX idx_seat_owner ON session_seat(owner_order_id,status);
CREATE TABLE order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    row_no VARCHAR(20) NOT NULL,
    seat_no VARCHAR(20) NOT NULL,
    price_zone VARCHAR(40) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    FOREIGN KEY (order_id,session_id) REFERENCES ticket_order(id,session_id),
    FOREIGN KEY (session_id,seat_id) REFERENCES session_seat(session_id,seat_id),
    CONSTRAINT uq_order_item_seat UNIQUE (order_id,seat_id),
    CHECK (unit_price >= 0)
);
-- The stable merchant request is separate from receipts: one request can receive
-- two distinct real gateway transactions, both of which must be persisted.
CREATE TABLE payment_intent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    merchant_request_no VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES ticket_order(id),
    UNIQUE (order_id,provider),
    UNIQUE (id,order_id),
    CHECK (amount >= 0)
);
CREATE TABLE payment_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_intent_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    gateway_transaction_id VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6),
    last_error VARCHAR(1000),
    FOREIGN KEY (payment_intent_id,order_id) REFERENCES payment_intent(id,order_id),
    FOREIGN KEY (order_id) REFERENCES ticket_order(id),
    UNIQUE (id,order_id),
    CHECK (amount >= 0)
);
CREATE TABLE refund_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(100) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    payment_record_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    gateway_refund_id VARCHAR(100) UNIQUE,
    gateway_succeeded_at DATETIME(6),
    local_completed_at DATETIME(6),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6),
    lease_until DATETIME(6),
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES ticket_order(id),
    FOREIGN KEY (payment_record_id,order_id) REFERENCES payment_record(id,order_id),
    UNIQUE (payment_record_id,type),
    CHECK (type IN ('NORMAL','LATE_PAYMENT','DUPLICATE_PAYMENT')),
    CHECK (amount >= 0 AND attempts >= 0)
);
CREATE INDEX idx_refund_recovery ON refund_record(status,next_attempt_at,lease_until);
CREATE TABLE outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6),
    lease_owner VARCHAR(100),
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6),
    CHECK (status IN ('PENDING','IN_FLIGHT','SENT','FAILED')),
    CHECK (attempts >= 0 AND attempts <= 10)
);
CREATE INDEX idx_outbox_dispatch ON outbox_event(status,next_attempt_at,lease_until,id);
CREATE TABLE consumed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    consumed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name,event_id)
);
-- Mock gateway facts are committed through an independent transaction before
-- booking mutation; the gateway adapter must never share its caller transaction.
CREATE TABLE mock_gateway_payment (
    gateway_transaction_id VARCHAR(100) PRIMARY KEY,
    merchant_request_no VARCHAR(100) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CHECK (amount >= 0)
);
CREATE INDEX idx_mock_merchant ON mock_gateway_payment(merchant_request_no);
CREATE TABLE mock_gateway_refund (
    refund_no VARCHAR(100) PRIMARY KEY,
    gateway_transaction_id VARCHAR(100) NOT NULL,
    gateway_refund_id VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CHECK (amount >= 0)
);
