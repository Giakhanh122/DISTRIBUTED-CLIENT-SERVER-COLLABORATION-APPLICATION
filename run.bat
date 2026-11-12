@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0app.ps1" -Mode cli %*
exit /b %errorlevel%
