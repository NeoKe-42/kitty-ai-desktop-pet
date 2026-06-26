@echo off
cd /d "%~dp0"
where pythonw >nul 2>nul
if errorlevel 1 (
  python app.py
) else (
  start "" pythonw app.py
)
