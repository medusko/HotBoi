# HotBoi SFP+ Telemetry Collector PowerShell Launcher
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "      HotBoi SFP+ Optical & Gateway Monitor" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

$pythonCmd = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCmd) {
    Write-Host "[ERROR] Python 3 was not found on your system." -ForegroundColor Red
    Write-Host "Please install Python from https://www.python.org/ or run 'winget install Python.Python.3.12'" -ForegroundColor Yellow
    exit 1
}

$scriptPath = Join-Path $PSScriptRoot "hotboi_collector.py"
python $scriptPath @args
