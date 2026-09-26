@echo off
rem MeshAI: double-click to open the chat running on your phones.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mesh.ps1" chat
if errorlevel 1 pause
