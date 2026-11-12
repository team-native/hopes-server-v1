CREATE TABLE recommendation_consents (
 user_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, policy_version VARCHAR(100) NOT NULL,
 updated_at DATETIME(6), version BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE question_recommendations (
 id BIGINT PRIMARY KEY, content VARCHAR(500) NOT NULL, department_id BIGINT, specialty_id BIGINT,
 sort_order INTEGER NOT NULL, is_active BOOLEAN NOT NULL,
 FOREIGN KEY(department_id) REFERENCES chat_categories(id), FOREIGN KEY(specialty_id) REFERENCES chat_categories(id)
);
INSERT INTO question_recommendations VALUES
 (1,'신입생이 학교생활을 준비할 때 무엇부터 챙기면 좋을까?',NULL,NULL,1,TRUE),
 (2,'기숙사 생활을 시작할 때 알아두면 좋은 점은?',NULL,NULL,2,TRUE),
 (3,'학교 프로젝트는 어떻게 시작하면 좋을까?',NULL,NULL,3,TRUE),
 (4,'전공을 아직 못 정했는데 어떻게 탐색하면 좋을까?',NULL,NULL,4,TRUE),
 (5,'친구들과 협업할 때 Git을 어떻게 사용하면 좋을까?',1,NULL,5,TRUE),
 (6,'IoT 프로젝트에서 센서와 서버를 어떻게 연결할까?',2,NULL,6,TRUE),
 (7,'인공지능 모델 학습 전에 어떤 기초를 공부하면 좋을까?',3,NULL,7,TRUE),
 (101,'iOS 앱 개발을 처음 시작하려면 무엇을 공부할까?',NULL,101,8,TRUE),
 (102,'Android 앱의 화면과 서버를 어떻게 연결할까?',NULL,102,9,TRUE),
 (103,'Frontend 프로젝트에서 컴포넌트를 어떻게 나눌까?',NULL,103,10,TRUE),
 (104,'Backend API를 설계할 때 무엇부터 정하면 좋을까?',NULL,104,11,TRUE),
 (105,'AI 프로젝트에 필요한 데이터를 어떻게 준비할까?',NULL,105,12,TRUE),
 (106,'IoT 장치의 통신 오류는 어떻게 확인할까?',NULL,106,13,TRUE),
 (107,'DevOps 자동 배포를 처음 만들 때 무엇이 필요할까?',NULL,107,14,TRUE),
 (108,'Design 작업에서 사용자의 흐름을 어떻게 정리할까?',NULL,108,15,TRUE),
 (109,'여러 개발 분야를 체험할 작은 프로젝트를 추천해줘',NULL,109,16,TRUE),
 (201,'소프트웨어개발과에서 Backend 프로젝트를 준비하려면?',1,104,17,TRUE);
