CREATE TABLE chat_categories (
 id BIGINT PRIMARY KEY, kind VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL,
 description VARCHAR(500) NOT NULL, sort_order INTEGER NOT NULL, is_active BOOLEAN NOT NULL
);
CREATE TABLE chat_preferences (
 user_id BIGINT PRIMARY KEY, department_id BIGINT, specialty_id BIGINT, version BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY(department_id) REFERENCES chat_categories(id),
 FOREIGN KEY(specialty_id) REFERENCES chat_categories(id)
);
INSERT INTO chat_categories VALUES
 (1,'DEPARTMENT','소프트웨어개발과','소프트웨어 개발 학과',1,TRUE),
 (2,'DEPARTMENT','스마트IoT과','스마트 IoT 학과',2,TRUE),
 (3,'DEPARTMENT','인공지능과','인공지능 학과',3,TRUE),
 (101,'SPECIALTY','iOS','iOS 앱 개발',1,TRUE),
 (102,'SPECIALTY','Android','Android 앱 개발',2,TRUE),
 (103,'SPECIALTY','Frontend','웹 프론트엔드',3,TRUE),
 (104,'SPECIALTY','Backend','서버 개발',4,TRUE),
 (105,'SPECIALTY','AI','인공지능',5,TRUE),
 (106,'SPECIALTY','IoT','사물인터넷',6,TRUE),
 (107,'SPECIALTY','DevOps','배포와 운영',7,TRUE),
 (108,'SPECIALTY','Design','UI/UX 디자인',8,TRUE),
 (109,'SPECIALTY','기타','그 외 관심 분야',9,TRUE);
