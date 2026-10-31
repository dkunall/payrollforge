#!/usr/bin/env bash
# Starts the compose stack with secrets generated on first use and kept outside the repo (mode 600).
# Reruns reuse them, so the database in an existing volume still accepts its password.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${PAYROLLFORGE_ENV_FILE:-$HOME/.config/payrollforge/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  {
    echo "PAYROLLFORGE_DB_PASSWORD=$(openssl rand -hex 24)"
    echo "PAYROLLFORGE_S3_ACCESS_KEY=pf$(openssl rand -hex 8)"
    echo "PAYROLLFORGE_S3_SECRET_KEY=$(openssl rand -hex 24)"
    echo "PAYROLLFORGE_FIELD_KEY=$(openssl rand -base64 32)"
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
