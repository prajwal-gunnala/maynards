@echo off
rem MeshAI: check the Host phone and show where the logs are.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mesh.ps1" status
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mesh.ps1" logs
pause
