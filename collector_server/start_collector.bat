@echo off
title HotBoi SFP+ Telemetry Collector
echo ========================================================
echo       HotBoi SFP+ Optical & Gateway Monitor
echo ========================================================
echo.

where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Python 3 was not found in your PATH!
    echo Please install Python 3 from https://www.python.org/ or the Microsoft Store.
    pause
    exit /b 1
)

echo Starting collector and web dashboard...
python "%~dp0hotboi_collector.py" %*

pause
