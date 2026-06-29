@ECHO OFF
SETLOCAL EnableDelayedExpansion
CD /D "%~dp0"

SET ROLE=TERTIARY
SET STOPPED=0

ECHO Stopping %ROLE% FIX Simulator...

TASKKILL /FI "WINDOWTITLE eq FINRA Simulator - TERTIARY*" /F 2>&1 | FINDSTR /I /C:"SUCCESS:" >NUL
IF !ERRORLEVEL! EQU 0 SET STOPPED=1

FOR /F "usebackq delims=" %%i IN (`powershell -NoProfile -Command "$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match '(?i)Finra_Simulator_Tertiary|fix-simulator\.jar.*tertiary|quickfixj-server-tertiary\.cfg' }; if ($procs) { $procs | ForEach-Object { $_.ProcessId } }"`) DO (
  IF NOT "%%i"=="" (
    ECHO Stopping Java process PID %%i ^(%ROLE%^)...
    TASKKILL /PID %%i /F >NUL 2>&1
    IF !ERRORLEVEL! EQU 0 SET STOPPED=1
  )
)

IF !STOPPED! EQU 0 (
  ECHO No %ROLE% simulator process found. It may already be stopped.
  EXIT /B 0
)

ECHO %ROLE% simulator stopped.
EXIT /B 0
