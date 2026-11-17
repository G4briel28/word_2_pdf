#!/usr/bin/env sh
# Lista las fuentes disponibles en ONLYOFFICE y, opcionalmente, las cruza con las fuentes usadas por un DOCX.
# Uso: ./scripts/check-fonts.sh [ruta/contrato.docx]   (requiere jq para el cruce)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}

INSTALLED=$(docker compose exec -T onlyoffice sh -c 'if command -v fc-list >/dev/null 2>&1; then fc-list : family | tr "," "\n"; else find /usr/share/fonts /var/www/onlyoffice/documentserver/core-fonts -type f \( -iname "*.ttf" -o -iname "*.otf" -o -iname "*.ttc" \) -exec basename {} \; 2>/dev/null; fi' | sed 's/^ *//;s/ *$//' | sort -u)
echo "Fuentes disponibles en ONLYOFFICE: $(echo "$INSTALLED" | grep -c . || true)"
echo "$INSTALLED" | sed 's/^/  /'

echo
echo "Fuentes personalizadas montadas (/usr/share/fonts/truetype/custom):"
docker compose exec -T onlyoffice sh -c 'ls -1 /usr/share/fonts/truetype/custom 2>/dev/null || true'

if [ $# -ge 1 ]; then
  command -v jq >/dev/null 2>&1 || { echo "Instale jq para cruzar fuentes"; exit 1; }
  API_KEY=$(grep '^APP_API_KEYS=' .env | cut -d= -f2 | cut -d, -f1)
  LAYOUT=$(curl -fsS -X POST "$BASE_URL/api/v1/conversion/docx/inspect" -H "X-API-Key: $API_KEY" \
    -H "Content-Type: application/octet-stream" --data-binary "@$1")
  NORM=$(echo "$INSTALLED" | sed -E 's/\.(ttf|otf|ttc)$//I' | tr -cd 'A-Za-z0-9\n' | tr 'A-Z' 'a-z')
  echo
  echo "Fuentes usadas por el DOCX:"
  echo "$LAYOUT" | jq -r '.fonts[]' | while IFS= read -r FONT; do
    KEY=$(echo "$FONT" | tr -cd 'A-Za-z0-9' | tr 'A-Z' 'a-z')
    if echo "$NORM" | grep -q "^$KEY"; then echo "  [OK]       $FONT"; else echo "  [AUSENTE]  $FONT -> ONLYOFFICE usará una fuente sustituta"; fi
  done
  echo
  echo "Secciones (puntos):"
  echo "$LAYOUT" | jq -r '.sections[] | "  \(.pageWidthTwips/20) x \(.pageHeightTwips/20) pt landscape=\(.landscape)"'
fi
