ALTER TABLE inquiries ADD COLUMN answer VARCHAR(4000);
ALTER TABLE inquiries ADD COLUMN reply_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE inquiries ADD COLUMN reply_request_id VARCHAR(100);
ALTER TABLE inquiries ADD COLUMN replied_at DATETIME(6);
ALTER TABLE inquiries ADD COLUMN reply_admin_id BIGINT;
ALTER TABLE inquiries ADD CONSTRAINT fk_inquiry_reply_admin FOREIGN KEY(reply_admin_id) REFERENCES users(id) ON DELETE SET NULL;
