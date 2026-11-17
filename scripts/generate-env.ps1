# Crea .env a partir de .env.example con secretos aleatorios criptográficamente seguros.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\generate-env.ps1 [-Force]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$example = Join-Path $root '.env.example'

if ((Test-Path $envFile) -and -not $Force) {
    Write-Host ".env ya existe. Use -Force para regenerarlo (invalida los secretos actuales)."
    exit 1
}

function New-HexSecret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return (($buffer | ForEach-Object { $_.ToString('x2') }) -join '')
}

$content = Get-Content $example -Raw
$content = $content -replace '(?m)^ONLYOFFICE_JWT_SECRET=.*$', ("ONLYOFFICE_JWT_SECRET=" + (New-HexSecret 32))
$content = $content -replace '(?m)^APP_API_KEYS=.*$', ("APP_API_KEYS=" + (New-HexSecret 24))
# UTF-8 sin BOM para que Docker Compose lo lea correctamente.
[System.IO.File]::WriteAllText($envFile, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ".env generado en $envFile"
Write-Host "La API key está en APP_API_KEYS dentro de .env (no la comparta por canales inseguros)."
