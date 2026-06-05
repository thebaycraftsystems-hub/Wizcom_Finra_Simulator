@ECHO OFF
REM Stop both Primary and Secondary FIX Simulator JVMs.
REM For a single role only, use stop-primary.bat or stop-secondary.bat.

CD /D "%~dp0"
CALL "%~dp0stop-primary.bat"
CALL "%~dp0stop-secondary.bat"
EXIT /B 0
