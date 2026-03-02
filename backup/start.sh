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


kill -9 `ps -eafw | grep 'WizFixSim' | grep -v grep | awk '{print $2}'| xargs`
java -Dname=WizFixSim -jar fix-simulator.jar quickfixj-server.cfg       # You send it in background
MyPID=$!                        # You sign it's PID
echo 
echo " PID is [ $MyPID ]"                     # You print to terminal
echo "kill $MyPID" > stop.sh  # Write the the command kill pid in stop.sh