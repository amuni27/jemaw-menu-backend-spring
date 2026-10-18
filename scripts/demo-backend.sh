#!/bin/sh
# Starts the backend for the local Product Owner demo (docs/AGAFARI_LOCAL_DEMO_GUIDE.md).
# In-memory H2 with fresh seed data on every start; no PostgreSQL, Cloudflare R2 or production resources.
#
# Test-only credentials live in the untracked .env.demo file (git-ignored by `.env.*`).
# It is generated with random values on first run and reused afterwards.
set -eu

cd "$(dirname "$0")/.."
ENV_FILE=.env.demo

if [ ! -f "$ENV_FILE" ]; then
  umask 077
  {
    echo "# Local demo only. Test credentials, never used outside this machine."
    echo "APP_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
    echo "DEMO_OWNER_PASSWORD=Demo-$(openssl rand -hex 6)"
  } > "$ENV_FILE"
  echo "Created $ENV_FILE with new test-only credentials."
fi

APP_JWT_SECRET=$(sed -n 's/^APP_JWT_SECRET=//p' "$ENV_FILE")
DEMO_OWNER_PASSWORD=$(sed -n 's/^DEMO_OWNER_PASSWORD=//p' "$ENV_FILE")

# Run from an empty working directory so the developer's ./application-local.yml is not imported,
# and with a minimal environment so no database or R2 variables leak in from the shell.
RUN_DIR="$PWD/target/demo-run"
mkdir -p "$RUN_DIR"

exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin:/usr/sbin:/sbin" \
  ${JAVA_HOME:+JAVA_HOME="$JAVA_HOME"} \
  APP_JWT_SECRET="$APP_JWT_SECRET" \
  DEMO_OWNER_PASSWORD="$DEMO_OWNER_PASSWORD" \
  sh ./mvnw -q -Pdemo spring-boot:run \
    -Dspring-boot.run.profiles=demo \
    -Dspring-boot.run.workingDirectory="$RUN_DIR"
