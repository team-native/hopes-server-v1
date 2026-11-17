CREATE INDEX idx_conversations_retention ON conversations(updated_at,id);
CREATE INDEX idx_inquiries_retention ON inquiries(created_at,id);
