#!/bin/bash
# AR Spatial Hub — Launch Script
# Starts RealSense, RTAB-Map, and rosbridge WebSocket

set -e

show_help() {
    cat <<'EOF'
Usage: bash launch_hub.sh [OPTIONS]

Starts the hub: RealSense sensor, RTAB-Map, and rosbridge WebSocket (port 9090).

Options:
  --new-map      Start a fresh map (deletes existing database)
  --localize     Relocalize against existing map (read-only)
  --with-phone   Also start the phone localizer (requires saved map)
  -h, --help     Show this help message

Examples:
  bash launch_hub.sh                  # Resume mapping from existing database
  bash launch_hub.sh --new-map        # Start fresh
  bash launch_hub.sh --localize       # Lock map, localize only
  bash launch_hub.sh --with-phone     # Map + phone relocalization
EOF
    exit 0
}

source /opt/ros/humble/setup.bash

RTABMAP_EXTRA=""
LOCALIZATION="false"
WITH_PHONE="false"

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
        --with-phone)
            WITH_PHONE="true"
            ;;
        -h|--help)
            show_help
            ;;
    esac
done

PIDS=()

cleanup() {
    echo ""
    echo "Shutting down..."
    kill "${PIDS[@]}" 2>/dev/null
    wait 2>/dev/null
    echo "Done."
}
trap cleanup EXIT INT TERM

# 1. RealSense
echo "=== Starting RealSense ==="
ros2 launch realsense2_camera rs_launch.py &
PIDS+=($!)

sleep 3

# 2. rosbridge WebSocket (port 9090)
echo "=== Starting rosbridge WebSocket ==="
ros2 launch rosbridge_server rosbridge_websocket_launch.xml &
PIDS+=($!)

sleep 2

# 3. Phone localizer (optional)
if [ "$WITH_PHONE" = "true" ]; then
    DB_PATH="${HOME}/.ros/rtabmap.db"
    if [ ! -f "$DB_PATH" ]; then
        echo "WARNING: --with-phone requires a saved map at $DB_PATH"
        echo "Phone localizer will not start. Build a map first."
    else
        echo "=== Starting phone localizer ==="
        ros2 launch rtabmap_launch rtabmap.launch.py \
            namespace:=phone_loc \
            localization:=true \
            rgb_topic:=/phone/image/compressed \
            camera_info_topic:=/phone/camera_info \
            frame_id:=phone_camera \
            approx_sync:=true \
            subscribe_depth:=false \
            database_path:="$DB_PATH" \
            Mem/IncrementalMemory:=false \
            Mem/InitWMWithAllNodes:=true \
            --ros-args -r /phone_loc/rtabmap/odom:=/phone/pose &
        PIDS+=($!)
    fi
fi

echo ""
echo "=== Hub running ==="
echo "  WebSocket: ws://$(hostname -I | awk '{print $1}'):9090"
echo "  Map database: ~/.ros/rtabmap.db"
[ "$WITH_PHONE" = "true" ] && echo "  Phone localizer: /phone/pose"
echo "  Press Ctrl+C to stop"
echo ""

# 4. RTAB-Map runs in foreground (keeps GUI window visible)
echo "=== Starting RTAB-Map ==="
ros2 launch rtabmap_launch rtabmap.launch.py \
    $RTABMAP_EXTRA \
    localization:=$LOCALIZATION \
    rgb_topic:=/camera/camera/color/image_raw \
    depth_topic:=/camera/camera/depth/image_rect_raw \
    camera_info_topic:=/camera/camera/color/camera_info \
    frame_id:=camera_link \
    approx_sync:=true
