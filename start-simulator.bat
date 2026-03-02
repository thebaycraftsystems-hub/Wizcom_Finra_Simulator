@ECHO OFF
REM Start FIX Simulator (Wizcom TRACE FIX Simulator)
REM Uses Maven to run the app. Stops any existing process on port 64034 first.

SET PORT=64034
SET PID=

REM Optional: stop any process already using the acceptor port
FOR /F "tokens=5" %%a IN ('netstat -ano ^| findstr ":%PORT%.*LISTENING" 2^>nul') DO (
  SET PID=%%a
  GOTO :kill_existing
)
GOTO :start

:kill_existing
IF NOT "%PID%"=="" (
  ECHO Port %PORT% in use by PID %PID%. Stopping it first...
  TASKKILL /PID %PID% /F >nul 2>&1
  timeout /t 2 /nobreak >nul
)

:start
ECHO Starting WIZCOM FIX Simulator...
ECHO.
cd /d "%~dp0"
mvn exec:java -q
pause
