ALTER TABLE messages ADD COLUMN client_request_id VARCHAR(100);
ALTER TABLE messages ADD COLUMN request_digest VARCHAR(64);
CREATE UNIQUE INDEX uq_message_retry ON messages(conversation_id,client_request_id);
CREATE TABLE chat_attachments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, message_id BIGINT,
 file_name VARCHAR(255) NOT NULL, mime_type VARCHAR(100) NOT NULL, size_bytes BIGINT NOT NULL,
 storage_key VARCHAR(36) NOT NULL UNIQUE, digest VARCHAR(64) NOT NULL, client_request_id VARCHAR(100),
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE(user_id,client_request_id), FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
);
CREATE INDEX idx_attachment_message ON chat_attachments(message_id);
CREATE INDEX idx_attachment_user_expiry ON chat_attachments(user_id,expires_at);
