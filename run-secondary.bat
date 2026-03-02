@echo off
cd /d "%~dp0"
echo Building fix-simulator.jar...
call mvn clean package -DskipTests
if not exist "target\fix-simulator.jar" (
  echo Error: target\fix-simulator.jar not found.
  exit /b 1
)
java -jar target\fix-simulator.jar secondary
pause
