ALTER TABLE messages
    ADD COLUMN client_platform VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' AFTER content;

CREATE INDEX idx_messages_role_platform_created
    ON messages (role, client_platform, created_at);
