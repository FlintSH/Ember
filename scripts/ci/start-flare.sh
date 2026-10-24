#!/usr/bin/env bash
# Starts a disposable Flare container next to the workflow's PostgreSQL service
# and writes the test fixture. Usage: start-flare.sh <fixture.json>
set -euo pipefail

fixture=$1
image=${FLARE_IMAGE:?}

docker run -d --name flare --network host \
  -e DATABASE_URL='postgresql://flare:flarepassword@localhost:5432/flare?schema=public' \
  -e NEXTAUTH_URL=http://localhost:3000 \
  -e NEXTAUTH_SECRET="$(openssl rand -base64 32)" \
  -e PORT=3000 \
  "$image"

if ! python3 scripts/flare-fixture/bootstrap.py --url http://localhost:3000 --out "$fixture" --timeout 300; then
  docker logs flare | tail -80
  exit 1
fi
