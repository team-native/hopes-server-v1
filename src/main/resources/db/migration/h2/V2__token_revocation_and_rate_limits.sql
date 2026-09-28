ALTER TABLE users ADD COLUMN token_version BIGINT DEFAULT 0 NOT NULL;

CREATE TABLE rate_limit_windows (
    key_hash VARCHAR(64) PRIMARY KEY,
    window_minute BIGINT NOT NULL,
    request_count INTEGER NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX idx_rate_limit_window_minute ON rate_limit_windows (window_minute);
