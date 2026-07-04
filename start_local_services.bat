@echo off
setlocal
cd /d "%~dp0"

echo Starting Huixiao AI local services without Docker...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0services\start_local_services.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
  echo Local services are ready. Emulator should use http://10.0.2.2:8080/api/v1
) else (
  echo Local service startup failed. Check services\logs for details.
)
echo.
pause
exit /b %EXIT_CODE%
