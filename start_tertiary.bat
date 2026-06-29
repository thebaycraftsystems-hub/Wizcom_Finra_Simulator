@echo off
CD /D "%~dp0"

ECHO Starting TERTIARY FIX Simulator...

IF NOT EXIST "target\fix-simulator.jar" (
  ECHO Error: target\fix-simulator.jar not found. Run: mvn package
  EXIT /B 1
)

start "FINRA Simulator - TERTIARY" cmd /k "cd /d "%~dp0" && java -Dname=Finra_Simulator_Tertiary -Dquickfixj.config=file:./quickfixj-server-tertiary.cfg -jar target\fix-simulator.jar quickfixj-server-tertiary.cfg"
EXIT /B 0
