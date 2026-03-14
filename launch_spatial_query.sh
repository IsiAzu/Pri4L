#!/bin/bash
# AR Spatial Hub — Spatial Query Service
# Starts the LLM-powered spatial query node.
#
# Prerequisites:
#   - Ollama running with llama3.2:3b pulled
#   - Hub running (launch_hub.sh)
#
# Usage:
#   Ask a question:  ros2 topic pub /hub/query std_msgs/msg/String "{data: 'where is the camera?'}" --once
#   Read response:   ros2 topic echo /hub/response

set -e

show_help() {
    cat <<'EOF'
Usage: bash launch_spatial_query.sh [OPTIONS]

Starts the spatial query service (Ollama + ROS2).

Prerequisites:
  - Ollama running with llama3.2:3b model
  - Hub running (launch_hub.sh)

Query interface:
  Ask:    ros2 topic pub /hub/query std_msgs/msg/String "{data: 'your question'}" --once
  Listen: ros2 topic echo /hub/response

Options:
  -h, --help     Show this help message
EOF
    exit 0
}

for arg in "$@"; do
    case $arg in
        -h|--help) show_help ;;
    esac
done

source /opt/ros/humble/setup.bash

# Check Ollama is running
if ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "ERROR: Ollama is not running. Start it with: ollama serve"
    exit 1
fi

echo "=== Starting spatial query service ==="
echo "  Model: llama3.2:3b"
echo "  Query topic: /hub/query (std_msgs/String)"
echo "  Response topic: /hub/response (std_msgs/String)"
echo "  Press Ctrl+C to stop"
echo ""

python3 "$(dirname "$0")/spatial_query.py"
