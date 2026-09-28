ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE rate_limit_windows (
    key_hash VARCHAR(64) NOT NULL,
    window_minute BIGINT NOT NULL,
    request_count INT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (key_hash),
    INDEX idx_rate_limit_window_minute (window_minute)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
