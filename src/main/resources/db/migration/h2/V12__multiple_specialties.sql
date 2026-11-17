CREATE TABLE chat_preference_specialties (
 user_id BIGINT NOT NULL,
 selection_order INTEGER NOT NULL,
 specialty_id BIGINT NOT NULL,
 PRIMARY KEY(user_id, selection_order),
 FOREIGN KEY(user_id) REFERENCES chat_preferences(user_id) ON DELETE CASCADE,
 FOREIGN KEY(specialty_id) REFERENCES chat_categories(id)
);
INSERT INTO chat_preference_specialties(user_id, selection_order, specialty_id)
 SELECT user_id, 0, specialty_id FROM chat_preferences WHERE specialty_id IS NOT NULL;
