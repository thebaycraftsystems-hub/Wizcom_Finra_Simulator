@ECHO OFF
SETLOCAL EnableDelayedExpansion
CD /D "%~dp0"

SET ROLE=SECONDARY
SET STOPPED=0

ECHO Stopping %ROLE% FIX Simulator...

REM run-both.bat console window
TASKKILL /FI "WINDOWTITLE eq FINRA Simulator - SECONDARY*" /F 2>&1 | FINDSTR /I /C:"SUCCESS:" >NUL
IF !ERRORLEVEL! EQU 0 SET STOPPED=1

REM java -jar ... secondary or quickfixj-server-secondary.cfg
FOR /F "usebackq delims=" %%i IN (`powershell -NoProfile -Command "$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match '(?i)fix-simulator\.jar.*secondary|quickfixj-server-secondary\.cfg' }; if ($procs) { $procs | ForEach-Object { $_.ProcessId } }"`) DO (
  IF NOT "%%i"=="" (
    ECHO Stopping Java process PID %%i ^(%ROLE%^)...
    TASKKILL /PID %%i /F >NUL 2>&1
    IF !ERRORLEVEL! EQU 0 SET STOPPED=1
  )
)

REM JAR ports (45007) and legacy failover port (64134)
CALL :kill_port 45007
CALL :kill_port 64134

IF !STOPPED! EQU 0 (
  ECHO No %ROLE% simulator process found. It may already be stopped.
  EXIT /B 0
)

ECHO %ROLE% simulator stopped.
EXIT /B 0

:kill_port
SET "PORT=%~1"
SET "PID="
FOR /F "tokens=5" %%a IN ('netstat -ano 2^>NUL ^| findstr /C:":%PORT% " ^| findstr "LISTENING"') DO (
  SET "PID=%%a"
  GOTO :kill_port_do
)
EXIT /B 0

:kill_port_do
ECHO Stopping process listening on port !PORT! ^(PID !PID!^)...
TASKKILL /PID !PID! /F >NUL 2>&1
IF !ERRORLEVEL! EQU 0 SET STOPPED=1
EXIT /B 0
