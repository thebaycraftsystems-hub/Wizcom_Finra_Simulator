@ECHO OFF
ECHO #####################################################################
ECHO This script is used to start  TRACE FIX SIMULATOR
ECHO Title 		: FIX_SIMULATOR
ECHO Copyright	: Copyright c 2019 Wizcom Corporation
ECHO Company		: Wizcom Corporation , U.S.A
ECHO Modification History :
ECHO Date		: FEB 24, 2019
ECHO Author		: K. KARTHIK
ECHO ######################################################################

ECHO # *********************************************************************
ECHO                        Starting TRACE FIX SIMULATOR
ECHO # *********************************************************************

IF "%1"=="start" (
    ECHO starting WIZCOM FIX simulator......
    start "WizFixSim" java -jar fix-simulator.jar quickfixj-server.cfg
) ELSE IF "%1"=="stop" (
    ECHO stoping WIZCOM FIX simulator......
    TASKKILL /FI "WINDOWTITLE eq WizFixSim"
) ELSE (
    ECHO please, use "run.bat start" or "run.bat stop"
)
pause