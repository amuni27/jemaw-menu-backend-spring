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
    echo "APP_PASSWORD_RESET_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
    echo "APP_EMAIL_VERIFICATION_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
    echo "DEMO_OWNER_PASSWORD=Demo-$(openssl rand -hex 6)"
  } > "$ENV_FILE"
  echo "Created $ENV_FILE with new test-only credentials."
fi

# AGF-S2-13A: older .env.demo files get the password-reset secret added once (separate from the JWT secret)
if ! grep -q '^APP_PASSWORD_RESET_SECRET=' "$ENV_FILE"; then
  umask 077
  echo "APP_PASSWORD_RESET_SECRET=$(openssl rand -base64 48 | tr -d '\n')" >> "$ENV_FILE"
fi

# AGF-S2-22: older .env.demo files get the email-verification secret added once (verification then works through the
# local demo outbox; no email is sent)
if ! grep -q '^APP_EMAIL_VERIFICATION_SECRET=' "$ENV_FILE"; then
  umask 077
  echo "APP_EMAIL_VERIFICATION_SECRET=$(openssl rand -base64 48 | tr -d '\n')" >> "$ENV_FILE"
fi
APP_JWT_SECRET=$(sed -n 's/^APP_JWT_SECRET=//p' "$ENV_FILE")
APP_PASSWORD_RESET_SECRET=$(sed -n 's/^APP_PASSWORD_RESET_SECRET=//p' "$ENV_FILE")
DEMO_OWNER_PASSWORD=$(sed -n 's/^DEMO_OWNER_PASSWORD=//p' "$ENV_FILE")
APP_EMAIL_VERIFICATION_SECRET=$(sed -n 's/^APP_EMAIL_VERIFICATION_SECRET=//p' "$ENV_FILE")
# AGF E4-M4: owners see plans only if APP_MONETIZATION_OWNER_PLANS_VISIBLE=true is set in the shell (E2E sets it).
# AGF E4-M1: the first Super Admin is created only if APP_ADMIN_BOOTSTRAP_EMAIL and APP_ADMIN_BOOTSTRAP_PASSWORD are set in
# the shell for this start (never stored in .env.demo by this script)
# AGF-S2-22: the publish gate stays off unless APP_EMAIL_VERIFICATION_ENFORCE=true is set in the shell or in .env.demo
APP_EMAIL_VERIFICATION_ENFORCE=${APP_EMAIL_VERIFICATION_ENFORCE:-$(sed -n 's/^APP_EMAIL_VERIFICATION_ENFORCE=//p' "$ENV_FILE")}

# Run from an empty working directory so the developer's ./application-local.yml is not imported,
# and with a minimal environment so no database or R2 variables leak in from the shell.
RUN_DIR="$PWD/target/demo-run"
mkdir -p "$RUN_DIR"

exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin:/usr/sbin:/sbin" \
  ${JAVA_HOME:+JAVA_HOME="$JAVA_HOME"} \
  APP_JWT_SECRET="$APP_JWT_SECRET" \
  APP_PASSWORD_RESET_SECRET="$APP_PASSWORD_RESET_SECRET" \
  DEMO_OWNER_PASSWORD="$DEMO_OWNER_PASSWORD" \
  APP_EMAIL_VERIFICATION_SECRET="$APP_EMAIL_VERIFICATION_SECRET" \
  APP_EMAIL_VERIFICATION_ENFORCE="${APP_EMAIL_VERIFICATION_ENFORCE:-false}" \
  ${APP_ADMIN_BOOTSTRAP_EMAIL:+APP_ADMIN_BOOTSTRAP_EMAIL="$APP_ADMIN_BOOTSTRAP_EMAIL"} \
  ${APP_ADMIN_BOOTSTRAP_PASSWORD:+APP_ADMIN_BOOTSTRAP_PASSWORD="$APP_ADMIN_BOOTSTRAP_PASSWORD"} \
  ${APP_MONETIZATION_OWNER_PLANS_VISIBLE:+APP_MONETIZATION_OWNER_PLANS_VISIBLE="$APP_MONETIZATION_OWNER_PLANS_VISIBLE"} \
  sh ./mvnw -q -Pdemo spring-boot:run \
    -Dspring-boot.run.profiles=demo \
    -Dspring-boot.run.workingDirectory="$RUN_DIR"
