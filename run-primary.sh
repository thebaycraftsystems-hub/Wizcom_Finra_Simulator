#!/bin/bash
cd "$(dirname "$0")"
echo "Building fix-simulator.jar..."
mvn clean package -DskipTests
if [ ! -f "target/fix-simulator.jar" ]; then
  echo "Error: target/fix-simulator.jar not found."
  exit 1
fi
java -jar target/fix-simulator.jar primary
