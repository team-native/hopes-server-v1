# Hopes 1.5 API

신규 기능명세 HOP-01~06의 서버 구현. 기존 Bearer 인증과 JSON DTO 직접 반환 방식을 유지한다. 모든 신규 API는 로그인해야 사용할 수 있다. 오류 응답은 `code`, `message`, 검증 실패 시 `errors`를 포함한다. `code`는 HTTP 상태명이다. 기존 앱의 텍스트 채팅 요청은 그대로 사용할 수 있다.

## 구현 범위

| 기능 | 서버 구현 | 앱에서 해야 하는 작업 |
|---|---|---|
| 이미지·PDF | 검증, 비공개 파일 저장, 메타데이터/열람/미전송 삭제, 채팅 원자 연결, OpenAI Responses 입력 전달, 이전 첨부 재조회 | 카메라/사진/파일 선택, 미리보기, 업로드·분석 진행과 재시도 UI |
| 학과·전공 | 활성 카탈로그, 독립 선택과 null 해제, 사용자별 서버 저장, 질문 맥락 반영 | 독립 선택 컨트롤, 비활성 선택 재선택 안내 |
| 질문 추천 | 기본/학과/전공/조합, 갱신 토큰·제외 ID, 기본 목록 대체, 명시적 동의에 따른 최근 질문 기반 템플릿 순위 | 추천 선택은 입력창만 채우고 자동 전송하지 않기 |
| Markdown | 원문 보존, contentFormat, 문단·목록·코드 형식 프롬프트 | 안전한 Markdown 렌더러, 코드 가로 스크롤·복사, 라이트/다크, HTML 실행 금지·링크 scheme 검증 |
| 익명 커뮤니티 | 게시판, 글/댓글 CRUD, 최신순 검색·페이지, 멱등 좋아요·취소, 신고, 내 이력 | 목록/상세/작성/신고 화면 |
| 현재 위치 | 정적 학교 기준 자료 조회, 오차·측정 시각을 고려한 비교, 대화 소유권 검증, 공개 승인 도면 조회 | CoreLocation 권한·측정, 지도 마커·오차 범위, 권한 거부 안내 |

GPS로 층·교실을 확정하지 않는다. QR/비콘, 실내 자동 추적, 길찾기, 커뮤니티 이미지·답글·운영자 제재 화면은 포함하지 않는다. 위치 요청은 LLM/대화/Discord/DB로 전달하거나 저장하지 않는다. 배포 프록시에서도 요청 본문을 로깅하지 않아야 한다.

## API 흐름

1. `GET /api/departments`, `GET /api/specialties`의 ID를 사용한다. 전체는 `null`. 학과와 전공 ID를 서로 대신 쓸 수 없다. 초기 전공 목록은 명세의 예시를 seed로 제공하며 DB의 `is_active`로 비활성화할 수 있다.
2. `GET/PATCH /api/chat-preferences`로 선택을 저장한다. PATCH에서 생략은 유지, null은 해제다. 기존 사용자 프로필의 major를 덮어쓰지 않는다. 질문/추천 요청에는 사용할 ID를 명시적으로 전달한다.
3. `GET /api/question-recommendations?departmentId=1&specialtyId=104&limit=4`. 반환된 `nextRefreshToken`을 다음 요청의 `refreshToken`으로 보내고 직전 노출 ID를 `excludeIds=1,2`로 보낸다. 전체 후보가 부족하면 이전 항목이 다시 나올 수 있다. limit 기본 4, 최대 20, 제외 ID 최대 100. 맞춤 후보가 없으면 기본 목록과 `fallbackUsed=true`.
4. 개인화는 `GET/PUT /api/privacy/recommendation-consent`. 현재 `policyVersion`을 읽고 `{ "enabled": true, "policyVersion": "1" }`로 동의한다. false로 철회하면 즉시 이력을 사용하지 않는다. 정책 버전이 바뀌면 재동의가 필요하다. 최근 3개 대화에서 각각 최근 10개 메시지 중 사용자 질문만 읽어 기존 추천 템플릿의 순위를 조절하며 별도 질문/파생 이력을 저장하지 않는다. 기본 동의는 false.
5. `POST /api/attachments`에 multipart `file`, 선택적으로 `clientRequestId`를 보낸다. 유효한 PNG/JPEG/PDF만 READY가 된다. HEIC/GIF/WebP와 암호화·손상 PDF는 거부한다. 원본 파일 이름은 경로를 제거하며 실제 저장 키는 무작위 UUID다. 다운로드는 Bearer 인증이 필요하고 no-store다.
6. `POST /api/chats/{id}/messages` 예시:

```json
{
  "content": "이 자료 요약해줘",
  "attachmentIds": [101, 102],
  "departmentId": 1,
  "specialtyId": 104,
  "clientRequestId": "client-generated-uuid"
}
```

텍스트는 필수다. 첨부만 전송은 아직 허용하지 않는다. AI 호출 성공 후 사용자 메시지·첨부 연결·답변을 한 트랜잭션에서 저장한다. 실패 시 연결하지 않아 재시도할 수 있다. 동일 대화의 같은 재시도 키와 같은 요청은 기존 결과를 반환하며 다른 본문은 409다. 경쟁 요청의 중복 키/첨부 연결 충돌도 409이며 재시도 후 기존 결과를 조회할 수 있다. AI 응답이 없는 비활성 모드에서 첨부 분석을 성공 처리하지 않는다(503). 동기 API이므로 요청 중 상태는 앱이 표시하고 저장 완료 응답의 analysisStatus는 COMPLETED다. 별도 비동기 작업 API는 없다.

질문 응답과 `GET /api/chats/{id}`에 attachments, analysisStatus, contentFormat이 추가된다. 첨부 만료 후에는 메타데이터가 EXPIRED이고 previewUrl은 null이다. 현재 요청과 최근 대화 첨부를 합쳐 설정된 개수·전체 용량 안에서 OpenAI에 전달한다. 오래되거나 만료된 파일은 후속 분석에서 제외될 수 있다. AI 답변은 MARKDOWN, 사용자 질문은 PLAIN_TEXT다. 링크 URL은 공개 URL이 아니므로 앱이 인증 헤더로 파일을 가져와야 한다.

## 커뮤니티

- `GET /api/community/boards`: 전체 및 사용자 테이블에 존재하는 기수의 게시판을 생성/조회한다. 다른 기수 게시판도 열람·작성 가능하다. 게시판 cohort는 작성자 기수가 아니다.
- `/posts`: GET 목록·검색, POST 작성. `/posts/{postId}`: GET 상세, PATCH 수정, DELETE 삭제.
- `/posts/{postId}/comments`: GET 목록, POST 작성. `/comments/{commentId}`: PATCH 수정, DELETE 삭제.
- `/posts/{postId}/like`: PUT 등록, DELETE 취소. 같은 요청 반복과 동시 요청에 대해 사용자별 1개만 저장한다.
- `/reports`: POST `{targetType:"POST" 또는 "COMMENT",targetId,reasonCode,detail?}`. reasonCode는 SPAM/ABUSE/PRIVACY/OTHER. 접수 202, 중복 409. 자동 숨김·운영자 제재는 별도 정책이 필요하다.
- `/me/posts`, `/me/comments`: GET 본인 기록. 타인 userId를 받지 않는다. 삭제된 원 게시글의 제목은 null로 반환한다.
- 글 제목 200자, 본문 10,000자, 댓글/신고 상세 2,000자, 검색어 200자. page 기본0, size 기본20/최대100. 게시글 최신순, 댓글 오래된순이며 ID를 보조 정렬로 사용한다. 작성/댓글 분당20회, 좋아요 분당60회, 신고 분당10회.
- 일반 응답에는 작성자 내부 ID·실명·학번·이메일·기수·지속 익명번호를 포함하지 않는다. isMine은 UI용이며 서버가 변경 권한을 다시 확인한다. 게시글·댓글 삭제는 삭제 상태로 바꾸고 내용을 비운다. 신고 증거 별도 보관/제재 정책은 미구현이다.

## 운영 설정

운영 DB는 Flyway V5~V8에서 카탈로그·선택·추천·동의·커뮤니티·첨부와 메시지 재시도 컬럼을 추가한다. H2와 MySQL 스크립트는 분리되어 있다. 서버 시작 시 자동 적용되므로 배포 전 DB 백업과 MySQL 검증이 필요하다. 사용자를 삭제하면 신규 데이터도 FK CASCADE로 제거된다. 첨부 실제 파일은 정기 정리된다.

| 환경 변수 | 초기값 |
|---|---|
| ATTACHMENT_DIRECTORY | ./data/attachments |
| ATTACHMENT_MAX_FILE_BYTES | 5,242,880 (5MiB) |
| ATTACHMENT_MAX_COUNT | 3 |
| ATTACHMENT_MAX_TOTAL_BYTES | 12,582,912 (12MiB) |
| ATTACHMENT_MAX_PDF_PAGES | 50 |
| ATTACHMENT_RETENTION_HOURS | 720 (30일) |
| ATTACHMENT_USER_QUOTA_BYTES | 104,857,600 (100MiB) |
| ATTACHMENT_MULTIPART_MAX_FILE_SIZE | 10MB |
| ATTACHMENT_MULTIPART_MAX_REQUEST_SIZE | 11MB |
| RECOMMENDATION_POLICY_VERSION | 1 |
| CAMPUS_DATA_PATH | 빈 값: 기준 자료 미등록 503 |
| CAMPUS_MAX_ACCURACY_METERS | 100 |
| CAMPUS_MAX_AGE_SECONDS | 120 |

파일 한도와 보관기간 등은 명세에서 미확정이어서 위 값을 초기 운영값으로 선택했다. 운영 확정 정책에 맞게 조정한다. 첨부 디렉터리는 정적 웹서버에서 공개하지 않는 서버 전용 디렉터리/영속 볼륨이어야 한다. 서버가 여러 대면 같은 비공개 파일 볼륨을 사용해야 한다. 1시간마다 만료 파일/메타데이터가 사라진 파일을 정리한다. 삭제 직후 물리 파일 정리까지 최대 약 1시간의 유예가 있다. 디렉터리와 DB는 함께 백업해야 한다. HEIC 변환과 별도 악성파일 검사 엔진은 포함하지 않는다.

## 학교 기준 자료

`CAMPUS_DATA_PATH`의 로컬 JSON 파일에 검증된 정적 자료를 등록한다. 실제 좌표를 임의로 넣지 않았다. 구조:

```text
school: { id, name, latitude, longitude, radiusMeters }
buildings: [{ id, name, latitude, longitude, publicationApproved, floorPlans: [
  { id, floor, label, url: HTTPS URL, facilities: [시설명] }
]}]
```

학교 경계는 등록 중심점과 반경으로 표현한다. 확정 측량 경계가 필요하면 polygon 모델로 확장해야 한다. 도면은 공개 승인이 있고 파일이 등록돼야 제공하며 없으면 available=false/items=[]/NOT_REGISTERED다. 현재 층을 알아내는 기능은 없다.

`POST /api/campus/location-check` 또는 `/api/chats/{id}/location-check`에 latitude, longitude, horizontalAccuracyMeters, measuredAt(ISO 8601)을 보낸다. 오래된 측정은 422, 30초보다 미래 시각/잘못된 좌표는 400, 기준 자료 미등록은 503. 정확도 초과는 LOW_ACCURACY, 오차 범위 전체가 학교 안이면 NEAR_SCHOOL, 전체가 밖이면 OUTSIDE, 경계와 겹치면 UNKNOWN. 응답 카드는 일시적으로 표시하며 원 좌표를 저장하지 않는다.

## 검증 범위

`mvn test` / `mvn package`. H2에서 실제 Flyway 마이그레이션과 JPA schema validate, MockMvc HTTP 계약·소유권·익명성·재시도·동시 좋아요, PNG/PDF 파일 검증, Fake AI Client로 바이너리 전달/실패 처리를 검증한다. 실제 OpenAI 호출과 키 연결은 보류 상태이며 자동 테스트에서는 모의 HTTP 서버로 계약만 검증한다. 앱 UI와 운영 MySQL·GSMSV 배포 확인은 별도다.

## OpenAI 전환 상태

기본 공급자는 openai다. OPENAI_API_KEY와 OPENAI_CHAT_MODEL은 비어 있으며 실제 연결·모델 선택·배포 활성화는 보류다. README의 설정 표와 모의 HTTP 검증 범위를 참고한다. 로컬 E5 임베딩·급식·기존 앱 DTO는 유지한다.

## 학과·전공·추천 데이터 보강

V9 마이그레이션에서 학과 3개·전공 9개의 관심 분야 설명을 보강하고 추천 질문 66개를 추가했다. 기존 17개 ID를 유지해 총 83개다. 기본 질문 8개, 학과별 4개씩, 전공별 4개씩, 학과·전공 독립 조합 27개별 1개씩 제공한다. 학교의 공식 교육과정이나 확인되지 않은 시설 정보를 추가한 것은 아니다.

H2에서 V1~V9 적용과 전체 질문 중복·길이·카탈로그 참조·27개 조합의 누락 여부를 검증한다. MySQL용 V9도 같은 DML이지만 운영 MySQL 적용은 아직 확인하지 않았다.
