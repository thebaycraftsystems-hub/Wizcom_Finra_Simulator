@echo off
cd /d "%~dp0"
if not exist "target\fix-simulator.jar" (
  echo Building fix-simulator.jar...
  call mvn package -q -DskipTests
)
if not exist "target\fix-simulator.jar" (
  echo Error: target\fix-simulator.jar not found. Run: mvn package
  exit /b 1
)
echo Starting PRIMARY simulator in this window...
start "FINRA Simulator - PRIMARY" cmd /k "cd /d "%~dp0" && java -jar target\fix-simulator.jar primary"
timeout /t 2 /nobreak >nul
echo Starting SECONDARY simulator in new window...
start "FINRA Simulator - SECONDARY" cmd /k "cd /d "%~dp0" && java -jar target\fix-simulator.jar secondary"
echo Both started. Close the two simulator windows to stop.
pause
