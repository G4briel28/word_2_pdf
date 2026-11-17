# Prueba de extremo a extremo REAL: levanta Docker Compose, espera a ONLYOFFICE, convierte el DOCX de ejemplo
# y valida que la respuesta sea un PDF.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\e2e.ps1 [-Docx samples\contrato-ejemplo.docx] [-NoBuild]
param(
    [string]$Docx = 'samples\contrato-ejemplo.docx',
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path '.env')) { throw "Falta .env. Ejecute scripts\generate-env.ps1" }
$apiKey = ((Get-Content .env | Where-Object { $_ -match '^APP_API_KEYS=' }) -replace '^APP_API_KEYS=', '').Split(',')[0]

if ($NoBuild) { docker compose up -d } else { docker compose up -d --build }
if ($LASTEXITCODE -ne 0) { throw "docker compose up falló" }

Write-Host "Esperando readiness (ONLYOFFICE puede tardar 1-3 minutos en el primer arranque)..."
$deadline = (Get-Date).AddMinutes(8)
do {
    Start-Sleep -Seconds 5
    try { $ready = (Invoke-WebRequest -UseBasicParsing "$BaseUrl/actuator/health/readiness" -TimeoutSec 5).StatusCode -eq 200 } catch { $ready = $false }
} until ($ready -or (Get-Date) -gt $deadline)
if (-not $ready) { docker compose ps; throw "El servicio no quedó listo a tiempo" }

New-Item -ItemType Directory -Force out | Out-Null
$name = [System.IO.Path]::GetFileNameWithoutExtension($Docx)
$outFile = Join-Path 'out' "$name.pdf"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
curl.exe -sS -f -X POST "$BaseUrl/api/v1/conversion/docx-to-pdf?filename=$name.docx" `
    -H "X-API-Key: $apiKey" -H "Content-Type: application/octet-stream" `
    --data-binary "@$Docx" -D (Join-Path 'out' "$name.headers.txt") -o $outFile
if ($LASTEXITCODE -ne 0) { throw "La conversión falló (ver out\$name.headers.txt)" }
$sw.Stop()

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $outFile))
$header = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(5, $bytes.Length))
if ($header -ne '%PDF-') { throw "La respuesta no es un PDF" }
Write-Host ("OK: {0} ({1:N0} bytes) en {2} ms" -f $outFile, $bytes.Length, $sw.ElapsedMilliseconds)
Get-Content (Join-Path 'out' "$name.headers.txt") | Where-Object { $_ -match '^(X-|Content-)' }
