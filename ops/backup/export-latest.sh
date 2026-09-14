#!/usr/bin/env bash
set -Eeuo pipefail

BACKUP_DIR=/var/backups/hopes/mysql
backup=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'hopes-*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)
if [[ -z "${backup:-}" ]]; then
    echo "전송할 DB 백업이 없습니다." >&2
    exit 1
fi

now=$(date +%s)
backup_age=$((now - $(stat -c %Y "$backup")))
if (( backup_age > 93600 )); then
    echo "최신 DB 백업이 26시간보다 오래됐습니다." >&2
    exit 1
fi

restore_marker="$BACKUP_DIR/.last-restore-verified"
if [[ ! -f "$restore_marker" ]] || (( now - $(stat -c %Y "$restore_marker") > 691200 )); then
    echo "주간 복원 검증 기록이 없거나 8일보다 오래됐습니다." >&2
    exit 1
fi

checksum="$backup.sha256"
test -f "$checksum"
gzip -t "$backup"
(
    cd "$BACKUP_DIR"
    sha256sum --check "$(basename "$checksum")" >/dev/null
)
exec cat "$backup"
