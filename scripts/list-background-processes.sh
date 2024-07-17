#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

echo "======== Docker containers ========"

docker ps --format 'table {{.ID}}\t{{.Names}}\t{{.Ports}}'

echo ""
echo "======== Tmux sessions ========"
tmux list-sessions

echo ""
echo "======== Java processes ========"

javaProcesses=$(jps -l)

echo "  PID | CLASS                                              |  PORT | NC"
echo "$javaProcesses" | while IFS= read -r line; do
  pid=$(echo "$line" | awk '{print $1}')
  mainClass=$(echo "$line" | awk '{print $2}')

  ports=$(lsof -nP -iTCP -a -p "$pid" | grep LISTEN | awk '{print $9}' | cut -d':' -f2)

  pid=$(printf "%5d" "$pid")
  mainClass=$(printf "%-*s" "50" "${mainClass##*/}")

  if [ -z "$ports" ]; then

    echo "$pid | $mainClass |   N/A |"
  else
    for port in $ports; do
        if nc -zv "localhost" "$port" &> /dev/null; then
          port=$(printf "%5d" "$port")
          echo "$pid | $mainClass | $port | ✅ "
        else
          port=$(printf "%5d" "$port")
          echo "$pid | $mainClass | $port | ❌ "
        fi
    done
  fi
done

echo ""
echo "======== Node processes ========"

node_pids=$(pgrep node)

echo "  PID | SCRIPT                         | PORT"
for pid in $node_pids; do
  node_ports=$(lsof -nP -iTCP -a -p "$pid" | grep LISTEN | awk '{print $9}')
  script=$(ps -p "$pid" -o args=)

  pid=$(printf "%5d" "$pid")
  script=$(printf "%-*s" "30" "${script##*/}")
  if [ -z "$node_ports" ]; then
    echo "$pid | $script |  N/A "
  else
    for port in $node_ports; do
      port=$(printf "%5d" "${port##*:}")
      echo "$pid | $script | $port"
    done
  fi
done
