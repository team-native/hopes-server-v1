# Hopes 운영 서버 위치와 점검

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
