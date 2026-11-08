#!/bin/sh
# Daily database backup: a compressed dump in deploy/backups, keeping the last 14 days.
# Run from cron, e.g.:  15 2 * * *  /opt/crm/CustomerRelationshipManagement/deploy/backup.sh >> /var/log/crm-backup.log 2>&1
set -eu
cd "$(dirname "$0")"
mkdir -p backups
file="backups/crm-$(date +%Y-%m-%d_%H%M).sql.gz"
docker compose exec -T db pg_dump -U crm -d crm --no-owner | gzip > "$file.tmp"
mv "$file.tmp" "$file"
find backups -name 'crm-*.sql.gz' -mtime +14 -delete
echo "$(date '+%F %T') backup written: $file ($(du -h "$file" | cut -f1))"
