#!/bin/bash
# Graceful stop of the SECONDARY simulator (failover / down-up testing).
# Sends SIGTERM so the JVM shutdown hook runs: Logout to gateway, quiesce for
# in-flight MsgSeqNum persistence, clean JDBC pool close. Use this instead of
# 'kill -9' so sequence numbers are always persisted correctly for the next start.
#
# Usage:
#   ./stop_secondary.sh          # graceful (recommended)
#   ./stop_secondary.sh --crash  # simulate an abrupt crash (kill -9, no shutdown hook)

cd /home/wizcom/apps/jpm/Trace/trace_UAT/fix_simulator

APP_NAME="WizFixSim_Praditha_secondary"
GRACE_SECS=25
MODE="graceful"
[ "$1" = "--crash" ] && MODE="crash"

PIDS=$(ps -eafw | grep "Dname=${APP_NAME} " | grep -v grep | awk '{print $2}' | xargs)
if [ -z "$PIDS" ]; then
  PIDS=$(ps -eafw | grep "$APP_NAME" | grep -v grep | awk '{print $2}' | xargs)
fi

if [ -z "$PIDS" ]; then
  echo "No SECONDARY process running."
  exit 0
fi

if [ "$MODE" = "crash" ]; then
  echo "CRASH TEST: kill -9 SECONDARY [ $PIDS ] (no graceful shutdown; recovery relies on per-message DB persistence + gap recovery on next Logon)."
  kill -9 $PIDS 2>/dev/null
  exit 0
fi

echo "Stopping SECONDARY [ $PIDS ] gracefully (SIGTERM) ..."
kill $PIDS 2>/dev/null
CSV=$(echo "$PIDS" | tr ' ' ',')
for i in $(seq 1 $GRACE_SECS); do
  alive=$(ps -p "$CSV" -o pid= 2>/dev/null | tr -d ' ')
  if [ -z "$alive" ]; then
    echo "SECONDARY stopped cleanly after ${i}s."
    exit 0
  fi
  sleep 1
done
echo "WARNING: SECONDARY still alive after ${GRACE_SECS}s; forcing kill -9."
kill -9 $PIDS 2>/dev/null
