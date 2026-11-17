# Fuentes adicionales para ONLYOFFICE

Este directorio se monta, en modo solo lectura, en el contenedor de ONLYOFFICE, en la ruta
`/usr/share/fonts/truetype/custom`. Al arrancar (`GENERATE_FONTS=true`), ONLYOFFICE regenera su catálogo de fuentes.

- Copie aquí los archivos `.ttf`, `.otf` o `.ttc` que usan sus contratos.
- Solo incluya fuentes cuya licencia permita instalarlas en un servidor y usarlas para generar documentos.
- **No versione los archivos de fuentes**: el `.gitignore` los excluye. Distribúyalos por un canal controlado.
- Después de añadir fuentes, ejecute `docker compose restart onlyoffice` y luego `scripts/check-fonts.ps1`.
- En Linux, los archivos deben poder leerse desde el contenedor (`chmod 644`).

Las fuentes que Word instala por defecto (Calibri, Cambria, Aptos…) son propiedad de Microsoft y su licencia
no permite redistribuirlas. Si un contrato las usa y no están disponibles, ONLYOFFICE las sustituye por otras,
y eso puede cambiar la longitud de las líneas y la paginación. Algunas alternativas libres con métricas
compatibles: Carlito (Calibri), Caladea (Cambria) y Liberation Sans/Serif (Arial/Times New Roman).
