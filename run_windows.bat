@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" bootstrap.py
    goto finished
)
if exist "D:\python.exe" (
    "D:\python.exe" bootstrap.py
    goto finished
)
where py >nul 2>nul
if not errorlevel 1 (
    py -3 bootstrap.py
    goto finished
)
where python >nul 2>nul
if not errorlevel 1 (
    python bootstrap.py
    goto finished
)
echo Python was not found. Install Python 3.12 x64 and run again.
:finished
if errorlevel 1 echo Startup failed. Copy the error displayed above.
pause
