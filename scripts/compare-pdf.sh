#!/usr/bin/env sh
# Compara el PDF de ONLYOFFICE con un PDF de referencia exportado desde Microsoft Word (herramienta de QA).
# Uso: ./scripts/compare-pdf.sh word.pdf onlyoffice.pdf [reports/comparacion] [anclas]
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR="$ROOT/target/word2pdf.jar"
[ -f "$JAR" ] || (cd "$ROOT" && ./mvnw -B -q -DskipTests package)
java -Dloader.main=pe.saeta.word2pdf.quality.PdfComparisonTool -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --reference "$1" --candidate "$2" --out "${3:-reports/comparacion}" --anchors "${4:-Firma}"
