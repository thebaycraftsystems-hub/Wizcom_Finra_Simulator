#!/bin/bash
echo "#####################################################################"
echo
echo "This script is used to start  TRACE FIX SIMULATOR (PRIMARY)"
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

APP_NAME="WizFixSim_Praditha"
CONFIG="quickfixj-server.cfg"
GRACE_SECS=25

# ---------------------------------------------------------------------------
# GRACEFUL STOP FIRST.
# We send SIGTERM (plain kill), NOT kill -9. SIGTERM runs the JVM shutdown hook
# which: sends Logout to the gateway, quiesces briefly so in-flight MsgSeqNum
# writes finish, and closes the JDBC pool cleanly. kill -9 (SIGKILL) bypasses all
# of that and can leave sequence numbers unpersisted -> gaps/lost trades on the
# next Logon. SIGKILL is used ONLY as a last resort if the process will not exit.
# ---------------------------------------------------------------------------
# Match PRIMARY only (exclude the *_secondary process which also contains APP_NAME).
PIDS=$(ps -eafw | grep "Dname=${APP_NAME} " | grep -v secondary | grep -v grep | awk '{print $2}' | xargs)
if [ -z "$PIDS" ]; then
  PIDS=$(ps -eafw | grep "$APP_NAME" | grep -v secondary | grep -v grep | awk '{print $2}' | xargs)
fi

if [ -n "$PIDS" ]; then
  echo "Stopping existing PRIMARY [ $PIDS ] gracefully (SIGTERM) ..."
  kill $PIDS 2>/dev/null
  CSV=$(echo "$PIDS" | tr ' ' ',')
  for i in $(seq 1 $GRACE_SECS); do
    alive=$(ps -p "$CSV" -o pid= 2>/dev/null | tr -d ' ')
    if [ -z "$alive" ]; then
      echo "PRIMARY stopped cleanly after ${i}s."
      break
    fi
    sleep 1
  done
  alive=$(ps -p "$CSV" -o pid= 2>/dev/null | tr -d ' ')
  if [ -n "$alive" ]; then
    echo "WARNING: PRIMARY still alive after ${GRACE_SECS}s; forcing kill -9 (sequence persistence may be incomplete)."
    kill -9 $PIDS 2>/dev/null
    sleep 2
  fi
else
  echo "No existing PRIMARY process found."
fi

java -Dname=${APP_NAME} -Dquickfixj.config=file:./${CONFIG} -jar fix-simulator.jar ${CONFIG} &

MyPID=$!                        # You sign it's PID
echo
echo " PID is [ $MyPID ]"                     # You print to terminal
