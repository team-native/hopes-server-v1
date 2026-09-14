#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

BACKUP_DIR=/var/backups/hopes/mysql
backup=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'hopes-*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)
if [[ -z "${backup:-}" ]]; then
    echo "복원 검증에 사용할 백업이 없습니다." >&2
    exit 1
fi

checksum="$backup.sha256"
test -f "$checksum"
gzip -t "$backup"
(
    cd "$BACKUP_DIR"
    sha256sum --check "$(basename "$checksum")"
)

restore_db="hopes_restore_verify_$(date -u +%s)_$$"
cleanup() {
    mysql --protocol=socket --user=root --execute="DROP DATABASE IF EXISTS \`$restore_db\`;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mysql --protocol=socket --user=root --execute="CREATE DATABASE \`$restore_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
gzip -dc "$backup" | mysql --protocol=socket --user=root "$restore_db"

required_tables=$(mysql --batch --skip-column-names --protocol=socket --user=root --execute="
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = '$restore_db'
      AND table_name IN ('users', 'conversations', 'messages', 'email_verifications', 'rate_limit_windows', 'inquiries');
")
if [[ "$required_tables" != "6" ]]; then
    echo "필수 테이블 복원 검증 실패: $required_tables/6" >&2
    exit 1
fi

mysqlcheck --protocol=socket --user=root --check "$restore_db" >/dev/null
marker="$BACKUP_DIR/.last-restore-verified"
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(basename "$backup")" > "$marker.tmp"
mv "$marker.tmp" "$marker"
echo "DB 복원 검증 완료: $(basename "$backup")"
