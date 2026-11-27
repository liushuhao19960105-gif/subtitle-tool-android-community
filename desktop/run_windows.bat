@echo off
cd /d "%~dp0"
where py >nul 2>nul
if not %ERRORLEVEL%==0 (
  echo This source package requires Python 3.11+.
  echo For an exe without Python, download the Windows build from GitHub Actions.
  pause
  exit /b 1
)
py -3 -m pip install -r requirements.txt
if errorlevel 1 (pause & exit /b 1)
py -3 app.py
