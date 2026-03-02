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
java -jar target\fix-simulator.jar primary
pause
