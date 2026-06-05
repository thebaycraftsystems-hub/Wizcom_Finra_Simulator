@ECHO OFF
SETLOCAL EnableDelayedExpansion
CD /D "%~dp0"

SET ROLE=PRIMARY
SET STOPPED=0

ECHO Stopping %ROLE% FIX Simulator...

REM run-both.bat console window (only count real kills)
TASKKILL /FI "WINDOWTITLE eq FINRA Simulator - PRIMARY*" /F 2>&1 | FINDSTR /I /C:"SUCCESS:" >NUL
IF !ERRORLEVEL! EQU 0 SET STOPPED=1

REM java -jar ... primary, default jar, quickfixj-server.cfg, or mvn exec Simulator
FOR /F "usebackq delims=" %%i IN (`powershell -NoProfile -Command "$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'fix-simulator\.jar|com\.wizcom\.fix\.simulator\.Simulator' -and $_.CommandLine -notmatch '(?i)secondary|quickfixj-server-secondary\.cfg' }; if ($procs) { $procs | ForEach-Object { $_.ProcessId } }"`) DO (
  IF NOT "%%i"=="" (
    ECHO Stopping Java process PID %%i ^(%ROLE%^)...
    TASKKILL /PID %%i /F >NUL 2>&1
    IF !ERRORLEVEL! EQU 0 SET STOPPED=1
  )
)

REM JAR ports (45001) and legacy/cwd cfg ports (64034)
CALL :kill_port 45001
CALL :kill_port 64034

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
