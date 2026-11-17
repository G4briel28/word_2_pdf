#!/usr/bin/env sh
# Crea .env a partir de .env.example con secretos aleatorios.
# Uso: ./scripts/generate-env.sh [--force]
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="$ROOT/.env"

if [ -f "$ENV_FILE" ] && [ "${1:-}" != "--force" ]; then
  echo ".env ya existe. Use --force para regenerarlo (invalida los secretos actuales)."
  exit 1
fi

if command -v openssl >/dev/null 2>&1; then
  JWT=$(openssl rand -hex 32)
  KEY=$(openssl rand -hex 24)
else
  JWT=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
  KEY=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
fi

sed -e "s/^ONLYOFFICE_JWT_SECRET=.*/ONLYOFFICE_JWT_SECRET=$JWT/" \
    -e "s/^APP_API_KEYS=.*/APP_API_KEYS=$KEY/" "$ROOT/.env.example" > "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo ".env generado en $ENV_FILE (permisos 600)."
