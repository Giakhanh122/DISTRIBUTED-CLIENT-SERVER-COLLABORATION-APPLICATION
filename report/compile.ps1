$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " BK HCMUT - Compiling Assignment 1 Report (LaTeX)" -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host ""

$tectonicExe = Join-Path $PSScriptRoot "bin\tectonic.exe"
if (-not (Test-Path $tectonicExe)) {
    Write-Error "bin\tectonic.exe not found at $tectonicExe"
    exit 1
}

Write-Host "Running Tectonic on main.tex..." -ForegroundColor Yellow
& $tectonicExe main.tex

if ($LASTEXITCODE -eq 0) {
    $pdf = Get-Item (Join-Path $PSScriptRoot "main.pdf")
    Write-Host ""
    Write-Host "====================================================" -ForegroundColor Green
    Write-Host "[SUCCESS] Report PDF generated successfully!" -ForegroundColor Green
    Write-Host "Path: $($pdf.FullName)" -ForegroundColor Green
    Write-Host "Size: $([math]::Round($pdf.Length / 1KB, 2)) KB" -ForegroundColor Green
    Write-Host "====================================================" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[ERROR] LaTeX compilation failed with exit code $LASTEXITCODE." -ForegroundColor Red
}
