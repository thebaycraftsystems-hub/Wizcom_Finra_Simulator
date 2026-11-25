@echo off
cd /d "%~dp0\.."
echo.
echo Secondary match/unmatch demo
echo  1. In another window, from this folder, run:  run-secondary.bat
echo     Wait until both JPMS and JPMB sessions are listening.
echo  2. This window then logs on as both gateways and runs the FINRA cases.
echo.
python scripts\secondary_match_unmatch_demo.py
echo.
pause
