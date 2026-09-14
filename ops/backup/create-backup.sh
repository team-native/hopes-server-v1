#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

BACKUP_DIR=/var/backups/hopes/mysql
LOCK_FILE=/run/lock/hopes-db-backup.lock
RETENTION_DAYS=30

mkdir -p "$BACKUP_DIR"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "이미 DB 백업이 실행 중입니다."
    exit 0
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
temporary="$BACKUP_DIR/.hopes-$timestamp.sql.gz.tmp"
backup="$BACKUP_DIR/hopes-$timestamp.sql.gz"
checksum="$backup.sha256"
trap 'rm -f "$temporary"' EXIT

mysqldump \
    --protocol=socket \
    --user=root \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    --events \
    --hex-blob \
    --set-gtid-purged=OFF \
    --no-tablespaces \
    hopes | gzip -9 > "$temporary"

gzip -t "$temporary"
mv "$temporary" "$backup"
(
    cd "$BACKUP_DIR"
    sha256sum "$(basename "$backup")" > "$(basename "$checksum")"
)

find "$BACKUP_DIR" -type f \
    \( -name 'hopes-*.sql.gz' -o -name 'hopes-*.sql.gz.sha256' \) \
    -mtime "+$RETENTION_DAYS" -delete

echo "DB 백업 완료: $backup"
