# Lista las fuentes disponibles en el contenedor de ONLYOFFICE y, opcionalmente, las cruza con las fuentes
# que usa un DOCX (vía endpoint /api/v1/conversion/docx/inspect).
# Uso: powershell -ExecutionPolicy Bypass -File scripts\check-fonts.ps1 [-Docx ruta\contrato.docx]
param(
    [string]$Docx,
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

# fc-list (Fontconfig) si está disponible; si no, inventario de archivos de fuentes.
$cmd = 'if command -v fc-list >/dev/null 2>&1; then fc-list : family | tr "," "\n"; else ' +
       'find /usr/share/fonts /var/www/onlyoffice/documentserver/core-fonts -type f ' +
       '\( -iname "*.ttf" -o -iname "*.otf" -o -iname "*.ttc" \) -exec basename {} \; 2>/dev/null; fi'
$installed = docker compose exec -T onlyoffice sh -c $cmd | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique
if ($LASTEXITCODE -ne 0) { throw "No se pudo consultar el contenedor onlyoffice (¿está levantado?)" }
Write-Host "Fuentes disponibles en ONLYOFFICE: $($installed.Count)"
$installed | ForEach-Object { "  $_" }

Write-Host "`nFuentes personalizadas montadas (/usr/share/fonts/truetype/custom):"
docker compose exec -T onlyoffice sh -c 'ls -1 /usr/share/fonts/truetype/custom 2>/dev/null || true'

if ($Docx) {
    $apiKey = ((Get-Content .env | Where-Object { $_ -match '^APP_API_KEYS=' }) -replace '^APP_API_KEYS=', '').Split(',')[0]
    $layout = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/conversion/docx/inspect" `
        -Headers @{ 'X-API-Key' = $apiKey } -ContentType 'application/octet-stream' `
        -InFile (Resolve-Path $Docx)
    $normalized = $installed | ForEach-Object { ($_ -replace '\.(ttf|otf|ttc)$', '' -replace '[^A-Za-z0-9]', '').ToLowerInvariant() }
    Write-Host "`nFuentes usadas por el DOCX:"
    foreach ($font in $layout.fonts) {
        $key = ($font -replace '[^A-Za-z0-9]', '').ToLowerInvariant()
        $found = $normalized | Where-Object { $_ -eq $key -or $_.StartsWith($key) }
        if ($found) { Write-Host "  [OK]       $font" } else { Write-Host "  [AUSENTE]  $font  -> ONLYOFFICE usará una fuente sustituta" -ForegroundColor Yellow }
    }
    Write-Host "`nSecciones (tamaño de página en puntos):"
    $layout.sections | ForEach-Object { "  {0} x {1} pt {2}" -f ($_.pageWidthTwips / 20), ($_.pageHeightTwips / 20), ($(if ($_.landscape) { '(horizontal)' } else { '(vertical)' })) }
}
