#!/bin/bash
# AR Spatial Hub — Test Anchor Publisher
# Publishes hardcoded test anchors to /hub/anchors at 1Hz.
# Used to verify the AR overlay pipeline end-to-end.
#
# Anchors are in the hub's (RTAB-Map) coordinate frame.
# The phone client transforms them to ARCore space via frame alignment.

set -e

show_help() {
    cat <<'EOF'
Usage: bash publish_test_anchors.sh [OPTIONS]

Publishes test anchors to /hub/anchors at 1Hz for AR overlay testing.

Prerequisites:
  - Hub must be running (launch_hub.sh)
  - ROS2 environment sourced

Options:
  -h, --help     Show this help message

The default anchors are placed at:
  - (0.5, 0.0, 0.0)   — 50cm in front of map origin
  - (0.0, 0.5, 0.0)    — 50cm left of map origin
  - (0.5, 0.5, -0.3)   — diagonal, 30cm below origin height
EOF
    exit 0
}

for arg in "$@"; do
    case $arg in
        -h|--help) show_help ;;
    esac
done

source /opt/ros/humble/setup.bash

echo "=== Publishing test anchors to /hub/anchors at 1Hz ==="
echo "  Anchor 1: (0.5, 0.0, 0.0)"
echo "  Anchor 2: (0.0, 0.5, 0.0)"
echo "  Anchor 3: (0.5, 0.5, -0.3)"
echo "  Press Ctrl+C to stop"
echo ""

ros2 topic pub /hub/anchors geometry_msgs/msg/PoseArray "{
  header: {
    frame_id: 'map'
  },
  poses: [
    {
      position: {x: 0.5, y: 0.0, z: 0.0},
      orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}
    },
    {
      position: {x: 0.0, y: 0.5, z: 0.0},
      orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}
    },
    {
      position: {x: 0.5, y: 0.5, z: -0.3},
      orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}
    }
  ]
}" --rate 1
