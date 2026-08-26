# Hopes DB 백업 정책

## 운영 정책

- 매일 오전 3시 30분(KST)에 MySQL 전체 논리 백업을 생성한다.
- 로컬 백업은 `/var/backups/hopes/mysql`에 30일 보관한다.
- 각 백업은 gzip 무결성 검사와 SHA-256 체크섬 검사를 통과해야 완료된 것으로 처리한다.
- 매주 일요일 오전 4시 30분(KST)에 최신 백업을 임시 DB에 실제 복원하고 필수 테이블과 MySQL 테이블 검사를 수행한다.
- 매일 오전 4시 10분(KST)에 GitHub Actions가 최신 백업을 수신하고 공개 인증서로 암호화한 뒤 아티팩트로 30일 보관한다.
- MySQL 바이너리 로그는 기존 설정대로 30일 보관해 시점 복구 자료로 사용한다.

목표 복구 시점(RPO)은 최대 24시간이다. 주간 복원 검증이 실패하거나 일일 백업이 실패하면 해당 systemd 서비스와 GitHub Actions 실행이 실패 상태로 남는다.

외부 백업 전송은 로컬 백업이 26시간보다 오래됐거나 마지막 실제 복원 검증이 8일보다 오래되면 실패한다. 따라서 GitHub Actions 실패 알림으로 로컬 백업과 주간 복원 검증의 이상도 함께 감지할 수 있다.

## 자동 작업 확인

```bash
systemctl list-timers 'hopes-db-*'
sudo systemctl status hopes-db-backup.service
sudo systemctl status hopes-db-restore-verify.service
sudo journalctl -u hopes-db-backup.service -u hopes-db-restore-verify.service
```

## 수동 백업과 복원 검증

```bash
sudo systemctl start hopes-db-backup.service
sudo systemctl start hopes-db-restore-verify.service
```

## 외부 백업 복호화

GitHub Actions의 `DB 외부 백업` 실행에서 아티팩트를 내려받고 압축을 푼 다음, 저장소에 포함되지 않는 `db-backup-recovery-private.pem`을 사용한다.

```bash
sha256sum --check hopes-YYYYMMDDTHHMMSSZ.sql.gz.cms.sha256
openssl cms -decrypt -binary -inform DER \
  -in hopes-YYYYMMDDTHHMMSSZ.sql.gz.cms \
  -inkey db-backup-recovery-private.pem \
  -recip ops/backup/db-backup-recovery-cert.pem \
  -out hopes-restore.sql.gz
gzip -t hopes-restore.sql.gz
```

복구 개인키를 잃으면 외부 백업을 복호화할 수 없다. 개인키는 GitHub나 서버에 올리지 말고 별도 암호화 저장소 또는 오프라인 저장 장치에 한 부 더 보관해야 한다.

운영 DB에 실제 복원할 때는 Spring 서비스를 중지하고 현재 DB를 추가 백업한 다음 진행해야 한다. 자동 복원은 데이터 덮어쓰기를 막기 위해 제공하지 않는다.
