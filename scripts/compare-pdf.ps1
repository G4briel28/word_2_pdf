# Compara el PDF de ONLYOFFICE con un PDF de referencia exportado desde Microsoft Word (herramienta de QA).
# Uso: powershell -ExecutionPolicy Bypass -File scripts\compare-pdf.ps1 -Reference word.pdf -Candidate onlyoffice.pdf `
#        [-Out reports\contrato] [-Anchors "Firma,EL CLIENTE"] [-Dpi 72]
param(
    [Parameter(Mandatory = $true)][string]$Reference,
    [Parameter(Mandatory = $true)][string]$Candidate,
    [string]$Out = 'reports\comparacion',
    [string]$Anchors = 'Firma',
    [int]$Dpi = 72
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'target\word2pdf.jar'
if (-not (Test-Path $jar)) {
    Push-Location $root; try { .\mvnw.cmd -B -q -DskipTests package } finally { Pop-Location }
}
java "-Dloader.main=pe.saeta.word2pdf.quality.PdfComparisonTool" -cp $jar `
    org.springframework.boot.loader.launch.PropertiesLauncher `
    --reference $Reference --candidate $Candidate --out $Out --anchors $Anchors --dpi $Dpi
exit $LASTEXITCODE
