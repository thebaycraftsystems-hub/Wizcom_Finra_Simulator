#!/bin/bash
echo "#####################################################################"
echo
echo "This script is used to start  TRACE FIX SIMULATOR"
echo
echo "Title 			:	WIZCOM_FIX_SIMULATOR"
echo "Copyright		:	Copyright (C) 2019 Wizcom Corporation"
echo "Company			:	Wizcom Corporation , U.S.A"
echo "Modification History "
echo "Date			:	FEB 24, 2019"
echo "Author			:	K. KARTHIK"
echo
echo
echo "****************************************"
echo " Starting TRACE FIX SIMULATOR.........."
echo "****************************************"

cd /home/wizcom/apps/jpm/Trace/trace_UAT/fix_simulator
pwd
kill -9 `ps -eafw | grep 'WizFixSim_Praditha' | grep -v grep | awk '{print $2}'| xargs`
#java -Dname=WizFixSim_Praditha -jar fix-simulator.jar primary quickfixj-server.cfg &

java -Dname=WizFixSim_Praditha -Dquickfixj.config=file:./quickfixj-server.cfg  -jar fix-simulator.jar quickfixj-server.cfg &

MyPID=$!                        # You sign it's PID
echo 
echo " PID is [ $MyPID ]"                     # You print to terminal
#echo "kill $MyPID" > stop.sh  # Write the the command kill pid in stop.sh



#java -jar your-app.jar --spring.config.location=file:./config-file.properties





