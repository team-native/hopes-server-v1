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
| POST | `/api/chats/{id}/messages` | 사용자 질문 저장 및 AI/급식 답변 생성 |
| PATCH | `/api/general` | 다크/라이트 테마 변경 |
| GET/PATCH | `/api/mypage` | 사용자 정보 조회/수정 |
| GET | `/api/setting/main` | 설정 조회 |
| PATCH | `/api/setting` | 커스텀 프롬프트 저장/대화 전체 삭제 |
| POST | `/api/password/request` | 비밀번호 재설정 인증 요청 |
| POST | `/api/password/reset` | 비밀번호 변경 |

로그인 이후 API에는 `Authorization: Bearer {accessToken}` 헤더가 필요합니다.
로그아웃하거나 비밀번호를 변경하면 해당 사용자에게 기존에 발급한 토큰은 즉시 무효화됩니다.

## AI (로컬 RAG + OpenAI)

답변 생성의 기본 공급자는 OpenAI Responses API다. RAG 임베딩은 기존 로컬 E5/DJL을 그대로 사용하며 OpenAI 임베딩으로 변경하지 않았다. 기존 SendMessageRequest/ChatResponse와 급식 조회 흐름도 유지한다.

1. 서버가 로컬 E5로 JSONL 안내 자료를 임베딩하고 캐시를 관리한다.
2. 텍스트 질문은 최근 대화로 급식 조회 의도를 분류한 뒤, 해당하면 나이스 식단을 사용한다. 다른 질문은 RAG 검색 결과·대화·채팅 맥락을 답변 모델에 전달한다.
3. 이미지/PDF는 검증된 바이트를 input_image/input_file의 Base64 data URI로 전달한다. 원본 저장소를 공개하거나 OpenAI Files에 영구 업로드하지 않는다.
4. 원시 Responses 응답의 output 메시지에서 output_text를 읽고 Markdown을 보존한다. 불완전·실패·거부·빈 답변은 성공으로 저장하지 않는다. store=false를 요청한다. 이는 응답 저장 옵션이며 공급자의 별도 데이터 보관 정책을 대체하지 않는다.

### 설정

키 입력과 실제 호출은 아직 보류된 상태다. 아래에는 변수 이름만 적으며 실제 키를 저장소에 넣지 않는다. 모델은 계정에서 Responses API·이미지·PDF 입력 지원을 확인한 뒤 지정한다. 모델을 임의로 확정하거나 최신 모델로 자동 변경하지 않는다.

```properties
AI_PROVIDER=openai
OPENAI_API_KEY=
OPENAI_CHAT_MODEL=
```

키와 답변 모델이 모두 있어야 OpenAI 기능이 활성화된다. 비어 있으면 외부 OpenAI 호출과 RAG 시작을 생략한다. 기존 비활성 모드에서는 일반 텍스트 질문만 저장되고 AI 답변은 생성되지 않으며, 첨부 분석은 503이다. 이 상태를 AI 정상 연동으로 간주하지 않는다. 모델을 지정할 때는 reasoning 토큰을 포함한 출력 한도도 확인한다.

| 환경 변수 | 기본값·설명 |
|---|---|
| AI_PROVIDER | openai. Gemini 유지가 필요하면 gemini를 명시하며 자동 fallback은 없음 |
| OPENAI_API_KEY | 빈 값. 실제 키 연결 보류 |
| OPENAI_CHAT_MODEL | 빈 값. Responses·이미지·PDF 지원 모델을 확인 후 지정 |
| OPENAI_CLASSIFICATION_MODEL | 빈 값이면 답변 모델 재사용. 필요하면 별도 분류 모델 지정 |
| OPENAI_CLASSIFICATION_MAX_OUTPUT_TOKENS | 256. 최소 16 |
| OPENAI_CONNECT_TIMEOUT_MS | 5000 |
| OPENAI_READ_TIMEOUT_MS | 60000 |
| AI_MAX_OUTPUT_TOKENS | 기존값 640. 최소16. 모델의 reasoning·출력 요구에 맞게 조정 |

기존 Gemini Client는 AI_PROVIDER=gemini를 명시하는 경우에만 선택된다. 그 경우에만 GEMINI_API_KEY와 AI_CHAT_MODEL을 사용한다. OpenAI 설정이 없다고 Gemini 키를 대신 사용하지 않는다.

### 검증

OpenAiClientTest는 MockRestServiceServer로 실제 직렬화된 HTTP 헤더·본문·이미지/PDF data URI·대화 role을 검증한다. 공급자 429, 전송 오류, 불완전·거부·빈 응답, 미설정 시 무호출도 검증한다. 실제 API 키나 외부 네트워크를 쓰지 않는다. 기존 AI 통합 테스트는 Fake Client를 사용한다.

공식 계약: [Responses 생성](https://developers.openai.com/api/reference/resources/responses/methods/create), [파일 입력](https://developers.openai.com/api/docs/guides/file-inputs), [이미지 입력](https://developers.openai.com/api/docs/guides/images-vision).

## 나이스 급식 연동

채팅에서 `오늘 급식`, `내일 점심`, `15일 식단`, `10월 2일 급식`, `2027년 3월 2일 급식`처럼 물으면 나이스 교육정보 개방 포털에서 광주소프트웨어마이스터고등학교 식단을 조회합니다. 날짜가 없으면 서울 기준 오늘, 일만 있으면 현재 연·월, 월·일만 있으면 현재 연도를 사용합니다. 등록된 식단이 없으면 `급식이 등록되지 않았습니다.`라고 답합니다.

`.env`의 `NEIS_API_KEY`는 선택 항목입니다. 비워도 제한된 샘플 호출은 가능하지만, 운영 환경에서는 나이스에서 발급한 인증키를 넣는 것을 권장합니다. 학교 기본값은 광주광역시교육청 `F10`, 광주소프트웨어마이스터고등학교 `7140392`입니다.

### 요청 횟수 제한

서버는 기본적으로 사용자별 AI 질문 분당 10회, 계정별 로그인 시도 분당 5회, 이메일별 인증번호 발송 분당 3회, 인증번호 확인 분당 5회로 제한합니다. 인증 관련 전체 요청은 IP별 분당 60회로 제한합니다. 카운터는 MySQL에 해시 키로 저장되어 서버가 여러 대여도 공유되며 만료된 윈도우는 자동 삭제됩니다. 필요하면 `.env`의 `RATE_LIMIT_*` 값을 조정할 수 있으며, 0 이하로 설정하면 해당 제한이 비활성화됩니다.

리버스 프록시 뒤에서 운영할 때만 `.env`의 `SERVER_FORWARD_HEADERS_STRATEGY=framework`로 변경하고, 외부 요청이 반드시 신뢰할 수 있는 프록시를 거쳐 들어오도록 구성해야 실제 클라이언트 IP 기준 제한이 적용됩니다.

### 운영 메모

- 인덱스 구축 완료 전 질문은 `503 AI가 아직 준비 중입니다` (시작 후 수 초 이내).
- 외부 AI 호출 실패 시 `502` 반환 — 트랜잭션 롤백으로 사용자 질문도 저장되지 않으므로 같은 내용으로 재시도하면 됩니다.
- `AI_MIN_SIMILARITY`(기본 0.55)는 로그의 `[ai] "질문" → sims:` 값을 보고 조정하세요. 임베딩 모델이 프로토타입(로컬 e5)과 달라져 임계값도 다시 잡은 것입니다.
- 답변 생성은 동기 방식입니다(응답까지 수 초). 스트리밍이 필요하면 SSE 엔드포인트 추가가 별도 작업으로 필요합니다.
