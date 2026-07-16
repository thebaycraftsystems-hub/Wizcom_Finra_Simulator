#!/bin/bash
echo "#####################################################################"
echo
echo "This script is used to start  TRACE FIX SIMULATOR (SECONDARY)"
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

APP_NAME="WizFixSim_Praditha_secondary"
CONFIG="quickfixj-server-secondary.cfg"
GRACE_SECS=25

# ---------------------------------------------------------------------------
# GRACEFUL STOP FIRST (SIGTERM, not kill -9). SIGTERM runs the JVM shutdown hook
# (send Logout, quiesce for in-flight MsgSeqNum writes, close JDBC pool cleanly).
# kill -9 bypasses that and can leave sequences unpersisted -> gaps on next Logon.
# SIGKILL is used ONLY as a last resort if the process will not exit.
# ---------------------------------------------------------------------------
PIDS=$(ps -eafw | grep "Dname=${APP_NAME} " | grep -v grep | awk '{print $2}' | xargs)
if [ -z "$PIDS" ]; then
  PIDS=$(ps -eafw | grep "$APP_NAME" | grep -v grep | awk '{print $2}' | xargs)
fi

if [ -n "$PIDS" ]; then
  echo "Stopping existing SECONDARY [ $PIDS ] gracefully (SIGTERM) ..."
  kill $PIDS 2>/dev/null
  CSV=$(echo "$PIDS" | tr ' ' ',')
  for i in $(seq 1 $GRACE_SECS); do
    alive=$(ps -p "$CSV" -o pid= 2>/dev/null | tr -d ' ')
    if [ -z "$alive" ]; then
      echo "SECONDARY stopped cleanly after ${i}s."
      break
    fi
    sleep 1
  done
  alive=$(ps -p "$CSV" -o pid= 2>/dev/null | tr -d ' ')
  if [ -n "$alive" ]; then
    echo "WARNING: SECONDARY still alive after ${GRACE_SECS}s; forcing kill -9 (sequence persistence may be incomplete)."
    kill -9 $PIDS 2>/dev/null
    sleep 2
  fi
else
  echo "No existing SECONDARY process found."
fi

java -Dname=${APP_NAME} -Dquickfixj.config=file:./${CONFIG} -jar fix-simulator.jar ${CONFIG} &

MyPID=$!                        # You sign it's PID
echo
echo " PID is [ $MyPID ]"                     # You print to terminal
