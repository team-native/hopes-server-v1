# Hopes 운영 서버 위치와 점검

## 앱·Swagger HTTPS 주소

- API base URL: `https://hopes-v1.https.gsmsv.site`
- Swagger: `https://hopes-v1.https.gsmsv.site/swagger-ui/index.html`
- OpenAPI: `https://hopes-v1.https.gsmsv.site/v3/api-docs`

GSMSV 인스턴스 116 (`hopes-server`, gsmgpu1)의 HTTPS 탭에서 위 도메인을 내부 80번 포트로 연결한다. 인증서는 GSMSV의 외부 Caddy에서 관리하며 Hopes VM은 nginx 80 → Spring 8080으로 전달한다. `service.gsmsv.site:22116`은 기존 HTTP 포트 접속 주소다. 앱의 HTTPS 기본 주소는 포트 없는 전용 도메인을 사용한다.

2026-11-17 인증서 검증을 켠 외부 요청으로 전용 도메인의 `/v3/api-docs` 200 응답을 확인했다. 인증서 검증을 끄거나 자체 서명 인증서를 앱에 추가할 필요가 없다.

## 백엔드 위치

- 실행 JAR: `/opt/hopes/hopes-server.jar`
- 환경 변수: `/opt/hopes/hopes.env`
- 데이터와 AI 캐시: `/opt/hopes/data`
- systemd 서비스: `/etc/systemd/system/hopes.service`
- 저장소의 서비스 원본: `ops/systemd/hopes.service`

```bash
sudo systemctl status hopes
sudo journalctl -u hopes -n 200 --no-pager
sudo ss -ltnp | grep ':8080'
```

운영 서비스는 MySQL보다 나중에 시작되지만 MySQL 재시작이 Hopes 종료로 전파되지 않도록 `Wants=mysql.service`를 사용한다. 비정상 종료와 일반 프로세스 종료 뒤에는 `Restart=always`로 자동 재기동한다.

서비스 파일을 다시 설치할 때는 다음과 같이 적용한다.

```bash
sudo install -o root -g root -m 0644 ops/systemd/hopes.service /etc/systemd/system/hopes.service
sudo systemctl daemon-reload
sudo systemctl enable --now hopes
```

## 문의 답변과 개인정보 보관

로그인 토큰은 운영에서 168시간(7일) 유효하며 refresh token은 없다. `POST /api/logout`은 계정의 tokenVersion을 올려 모든 기존 기기의 토큰을 무효화한다. 로그인 유지 체크는 서버 만료 시간을 바꾸지 않는다. 체크 해제 시 앱은 토큰을 메모리에만 두고 프로세스 종료 시 삭제하는 방식으로 구현한다. 오프라인 로그아웃은 로컬 토큰을 먼저 지우고, 서버 무효화 요청을 보류할 경우 기존 토큰으로 처리한다. 네트워크 복구 후 보류한 로그아웃을 먼저 완료하고 새 로그인을 진행해야 한다. 새 로그인 뒤 늦게 보낸 로그아웃도 같은 계정의 새 토큰을 무효화할 수 있다. 로그아웃의 401은 만료·무효화된 토큰으로 보고 보류 요청을 종료한다. 앱·모바일 코드는 이 저장소에서 변경하지 않는다.

- 담당: 네이티브 팀, `team.native.official@gmail.com`. 운영자 계정은 `ADMIN` 권한으로 Swagger에 로그인한다.
- `GET /api/admin/inquiries`와 `GET /api/admin/inquiries/{id}`로 문의를 확인하고, `POST /api/admin/inquiries/{id}/reply`에 `{"content":"답변", "clientRequestId":"고유한-요청-ID"}`를 보낸다.
- 메일은 운영 SMTP에서 보내며 Reply-To는 팀 공식 주소다. 동일 문의·동일 요청 ID·동일 내용의 재시도는 발송 상태만 반환한다. 다른 답변 요청은 409다.
- `SENT`는 SMTP 인계 성공이며 수신함 도착을 보장하지 않는다. `SENDING`이 오래 남거나 `DELIVERY_UNKNOWN`이면 발신함·반송 메일을 먼저 확인한다. 중복 메일 방지를 위해 자동 재발송하지 않는다. 추가 답변은 팀 공식 메일에서 직접 처리한다.
- 사용자는 `GET /api/setting/inquiries`에서 자신의 문의·답변만 확인한다.
- 문의는 접수일, 대화는 마지막 변경일부터 365일 보관한다. 매일 04:10 KST에 정리하며, 대량 적체 방지를 위해 한 번에 각 10,000개까지 처리한다. `INQUIRY_RETENTION_DAYS`, `CHAT_RETENTION_DAYS`로 변경하면 공개 방침도 함께 수정한다.
- 개인정보 처리방침 링크: `https://hopes-v1.https.gsmsv.site/api/privacy/policy`. 앱에는 이 웹 링크를 연결한다.
- Discord 질문 로그는 DB 정리와 자동 연동되지 않는다. 외부 로그 삭제 요청이 들어오면 운영자가 해당 메시지 삭제를 별도로 처리해야 한다. 백업 복원 후에는 탈퇴·삭제 요청을 재반영한다.

## DB 백업 위치

- 실제 백업 파일: `/var/backups/hopes/mysql` (root 전용)
- 운영 디렉터리 링크: `/opt/hopes/backups`
- 백업 스크립트: `/opt/hopes/backup`
- 서버 내 안내 파일: `/opt/hopes/BACKUP_LOCATION.txt`

```bash
sudo ls -lah /opt/hopes/backups/
sudo systemctl status hopes-db-backup.timer
sudo systemctl status hopes-db-restore-verify.timer
```

백업 파일에는 계정 데이터가 포함되므로 일반 사용자에게 읽기 권한을 주지 않는다. 자세한 보관 기간과 복구 검증 정책은 `docs/DB_BACKUP.md`를 참고한다.

## 외부 502 점검

외부 프록시는 Nginx를 거쳐 `127.0.0.1:8080`으로 요청을 전달한다. 502가 발생하면 아래 순서로 확인한다.

1. `sudo systemctl is-active hopes`
2. `sudo ss -ltnp | grep ':8080'`
3. `curl -I http://127.0.0.1:8080/v3/api-docs`
4. `sudo journalctl -u hopes -n 200 --no-pager`
