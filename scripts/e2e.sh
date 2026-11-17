#!/usr/bin/env sh
# Prueba de extremo a extremo REAL contra Docker Compose.
# Uso: ./scripts/e2e.sh [samples/contrato-ejemplo.docx]
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
DOCX=${1:-samples/contrato-ejemplo.docx}
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}

[ -f .env ] || { echo "Falta .env. Ejecute ./scripts/generate-env.sh"; exit 1; }
API_KEY=$(grep '^APP_API_KEYS=' .env | cut -d= -f2 | cut -d, -f1)

docker compose up -d --build

echo "Esperando readiness (ONLYOFFICE puede tardar 1-3 minutos en el primer arranque)..."
i=0
until curl -fsS "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -gt 96 ] && { docker compose ps; echo "El servicio no quedó listo a tiempo"; exit 1; }
  sleep 5
done

mkdir -p out
NAME=$(basename "$DOCX" .docx)
START=$(date +%s)
curl -sS -f -X POST "$BASE_URL/api/v1/conversion/docx-to-pdf?filename=$NAME.docx" \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/octet-stream" \
  --data-binary "@$DOCX" -D "out/$NAME.headers.txt" -o "out/$NAME.pdf"
END=$(date +%s)

[ "$(head -c 5 "out/$NAME.pdf")" = "%PDF-" ] || { echo "La respuesta no es un PDF"; exit 1; }
echo "OK: out/$NAME.pdf ($(wc -c < "out/$NAME.pdf") bytes) en $((END - START)) s"
grep -iE '^(x-|content-)' "out/$NAME.headers.txt" || true
