#!/bin/bash
# AR Spatial Hub — Launch Script
# Starts RealSense (with IMU), RTAB-Map, and rosbridge WebSocket
# Run with: bash launch_hub.sh
#
# Options:
#   --new-map    Start a fresh map (deletes existing)
#   --localize   Relocalize against existing map

set -e

source /opt/ros/humble/setup.bash

RTABMAP_EXTRA=""
LOCALIZATION="false"

for arg in "$@"; do
    case $arg in
        --new-map)
            RTABMAP_EXTRA="rtabmap_args:=--delete_db_on_start"
            echo "Starting fresh map (deleting existing database)"
            ;;
        --localize)
            LOCALIZATION="true"
            echo "Localization mode (using existing map)"
            ;;
    esac
done

cleanup() {
    echo ""
    echo "Shutting down..."
    kill $PID_REALSENSE $PID_BRIDGE 2>/dev/null
    wait 2>/dev/null
    echo "Done."
}
trap cleanup EXIT INT TERM

# 1. RealSense
echo "=== Starting RealSense ==="
ros2 launch realsense2_camera rs_launch.py &
PID_REALSENSE=$!

sleep 3

# 2. rosbridge WebSocket (port 9090)
echo "=== Starting rosbridge WebSocket ==="
ros2 launch rosbridge_server rosbridge_websocket_launch.xml &
PID_BRIDGE=$!

sleep 2

echo ""
echo "=== Hub running ==="
echo "  WebSocket: ws://$(hostname -I | awk '{print $1}'):9090"
echo "  Map database: ~/.ros/rtabmap.db"
echo "  Press Ctrl+C to stop"
echo ""

# 3. RTAB-Map runs in foreground (keeps GUI window visible)
echo "=== Starting RTAB-Map ==="
ros2 launch rtabmap_launch rtabmap.launch.py \
    $RTABMAP_EXTRA \
    localization:=$LOCALIZATION \
    rgb_topic:=/camera/camera/color/image_raw \
    depth_topic:=/camera/camera/depth/image_rect_raw \
    camera_info_topic:=/camera/camera/color/camera_info \
    frame_id:=camera_link \
    approx_sync:=true
