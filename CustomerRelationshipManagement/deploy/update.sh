#!/bin/sh
# Release a new version: back up the database, get the latest code, rebuild and restart.
set -eu
cd "$(dirname "$0")"
./backup.sh
git pull --ff-only
docker compose up -d --build
docker image prune -f
docker compose ps
