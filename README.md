# Hopes Kotlin Server

Notion 기능명세서와 API 명세서를 기준으로 만든 Kotlin/Spring Boot REST API입니다.

운영 서버의 백엔드·백업 위치와 장애 점검은 [docs/SERVER_OPERATIONS.md](docs/SERVER_OPERATIONS.md), DB 백업·보관·복원 검증 정책은 [docs/DB_BACKUP.md](docs/DB_BACKUP.md)를 참고하세요.

## 준비

- JDK 21
- Maven 3.9+
- MySQL 8 이상

MySQL을 실행한 뒤 프로젝트 루트의 `.env`에서 아래 두 값을 자신의 MySQL 계정에 맞게 입력합니다.

```properties
DATABASE_USERNAME=root
DATABASE_PASSWORD=MySQL을_설치할_때_정한_비밀번호
```

기본 접속 주소는 다음과 같습니다. 해당 계정에 데이터베이스 생성 권한이 있으면 `hopes` 데이터베이스가 자동 생성됩니다.

```properties
DATABASE_URL=jdbc:mysql://localhost:3306/hopes?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul
```

## 실행

```bash
mvn spring-boot:run
```

서버 기본 주소는 `http://localhost:8080`입니다. DB 스키마는 Flyway가 버전별로 생성·갱신하고 JPA가 시작 시 구조를 검증합니다.

## Gmail 인증메일

발신자는 `team.native.official@gmail.com`으로 설정되어 있습니다. `.env`의 `MAIL_PASSWORD=` 뒤에 공백 없이 Google 앱 비밀번호를 입력하면 매 요청마다 생성되는 랜덤 6자리 인증번호가 전송됩니다.

## 주요 API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/signup/email-verifications` | 학교 이메일 인증번호 요청 |
| POST | `/api/signup/email-verifications/confirm` | 인증번호 확인 |
| POST | `/api/signup` | 회원가입 |
| POST | `/api/login` | 로그인 및 Bearer 토큰 발급 |
| GET | `/api/main?searchKeyword=&page=0&size=50` | 대화 목록/검색(최대 100개) |
| POST | `/api/chats` | 새 대화 생성 |
| GET | `/api/chats/{id}?messagePage=0&messageSize=50` | 대화 불러오기(최신 페이지부터, 최대 100개) |
| POST | `/api/chats/{id}/messages` | 사용자 질문 저장 |
| PATCH | `/api/general` | 다크/라이트 테마 변경 |
| GET/PATCH | `/api/mypage` | 사용자 정보 조회/수정 |
| GET | `/api/setting/main` | 설정 조회 |
| PATCH | `/api/setting` | 커스텀 프롬프트 저장/대화 전체 삭제 |
| POST | `/api/password/request` | 비밀번호 재설정 인증 요청 |
| POST | `/api/password/reset` | 비밀번호 변경 |

로그인 이후 API에는 `Authorization: Bearer {accessToken}` 헤더가 필요합니다.
로그아웃하거나 비밀번호를 변경하면 해당 사용자에게 기존에 발급한 토큰은 즉시 무효화됩니다.

## AI (RAG + Gemini)

`POST /api/chats/{id}/messages`가 이제 사용자 질문 저장 후 AI 답변까지 생성해 저장하고, 두 메시지가 포함된 대화 전체를 반환합니다.

동작 방식 (프로토타입 `hopes/backend` 구조 이식):

1. 서버 시작 시 `src/main/resources/data/gsm_guide_rag_chunks.jsonl`(선배 설문 126개 청크)을 `gemini-embedding-001`로 임베딩해 메모리 인덱스 구축. 결과는 `./data/embeddings_cache.json`에 캐시되어 재시작 시 API 호출 없이 재사용.
2. 질문마다: 약어 확장(기자위→기숙사자치위원회 등) → 질문 임베딩 → 코사인 유사도 상위 3개 청크 검색(임계값 미달 시 0개) → "GSM 선배" 페르소나 + 검색 청크 + 최근 대화 20턴 + 사용자 정보/커스텀 프롬프트로 `gemini-3.1-flash-lite` 호출.
3. 검색 결과가 없으면 프롬프트가 사실 창작을 금지하고 "모른다"고 답하게 유도.

### 설정

`.env`에 [Google AI Studio](https://aistudio.google.com/apikey)에서 발급한 키를 넣습니다.

```properties
GEMINI_API_KEY=발급받은_키
```

키가 없으면 AI만 비활성화되고 서버는 기존처럼 질문 저장만 합니다.

선택 항목(기본값 있음): `AI_ENABLED`, `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_TOP_K`, `AI_MIN_SIMILARITY`, `AI_HISTORY_MAX_TURNS`, `AI_CHUNKS_PATH`, `AI_CACHE_PATH`

### 요청 횟수 제한

서버는 기본적으로 사용자별 AI 질문 분당 10회, 계정별 로그인 시도 분당 5회, 이메일별 인증번호 발송 분당 3회, 인증번호 확인 분당 5회로 제한합니다. 인증 관련 전체 요청은 IP별 분당 60회로 제한합니다. 카운터는 MySQL에 해시 키로 저장되어 서버가 여러 대여도 공유되며 만료된 윈도우는 자동 삭제됩니다. 필요하면 `.env`의 `RATE_LIMIT_*` 값을 조정할 수 있으며, 0 이하로 설정하면 해당 제한이 비활성화됩니다.

리버스 프록시 뒤에서 운영할 때만 `.env`의 `SERVER_FORWARD_HEADERS_STRATEGY=framework`로 변경하고, 외부 요청이 반드시 신뢰할 수 있는 프록시를 거쳐 들어오도록 구성해야 실제 클라이언트 IP 기준 제한이 적용됩니다.

### 운영 메모

- 인덱스 구축 완료 전 질문은 `503 AI가 아직 준비 중입니다` (시작 후 수 초 이내).
- Gemini 호출 실패 시 `502` 반환 — 트랜잭션 롤백으로 사용자 질문도 저장되지 않으므로 같은 내용으로 재시도하면 됩니다.
- `AI_MIN_SIMILARITY`(기본 0.55)는 로그의 `[ai] "질문" → sims:` 값을 보고 조정하세요. 임베딩 모델이 프로토타입(로컬 e5)과 달라져 임계값도 다시 잡은 것입니다.
- 답변 생성은 동기 방식입니다(응답까지 수 초). 스트리밍이 필요하면 SSE 엔드포인트 추가가 별도 작업으로 필요합니다.
