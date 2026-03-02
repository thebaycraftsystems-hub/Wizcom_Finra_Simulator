@ECHO OFF
REM Stop FIX Simulator by killing the process listening on port 64034.
REM Use this when the simulator was started with "mvn exec:java" or when stop.bat does not work.

SET PORT=64034
SET PID=

FOR /F "tokens=5" %%a IN ('netstat -ano ^| findstr ":%PORT%.*LISTENING"') DO (
  SET PID=%%a
  GOTO :found
)

:found
IF "%PID%"=="" (
  ECHO No process found listening on port %PORT%. Simulator may already be stopped.
  EXIT /B 0
)

ECHO Stopping FIX Simulator (PID %PID% on port %PORT%)...
TASKKILL /PID %PID% /F
IF %ERRORLEVEL% EQU 0 (
  ECHO Simulator stopped.
) ELSE (
  ECHO Failed to stop process. Try running as Administrator.
  EXIT /B 1
)
