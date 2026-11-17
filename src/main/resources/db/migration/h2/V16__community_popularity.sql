ALTER TABLE community_posts ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
CREATE TABLE community_post_views (
 post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, viewed_on DATE NOT NULL,
 PRIMARY KEY(post_id,user_id,viewed_on),
 FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_post_views_day ON community_post_views(viewed_on);
