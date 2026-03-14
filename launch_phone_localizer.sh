#!/bin/bash
# AR Spatial Hub — Phone Localizer (standalone)
# Runs a second RTAB-Map instance in localization-only mode
# that relocalizes the phone's camera against the hub's saved map.
#
# Prefer: bash launch_hub.sh --with-phone
# Use this script only if the hub is already running and you want to
# start the phone localizer separately.

set -e

show_help() {
    cat <<'EOF'
Usage: bash launch_phone_localizer.sh [OPTIONS]

Starts phone relocalization against the hub's saved map.

Prerequisites:
  - Hub must have a saved map (~/.ros/rtabmap.db)
  - Phone must be streaming to /phone/image/compressed and /phone/camera_info
  - rosbridge must be running (launch_hub.sh)

Output:
  - /phone/pose (geometry_msgs/PoseStamped) — phone position in map frame

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

DB_PATH="${HOME}/.ros/rtabmap.db"

if [ ! -f "$DB_PATH" ]; then
    echo "ERROR: No map database found at $DB_PATH"
    echo "Run launch_hub.sh first to build a map."
    exit 1
fi

cleanup() {
    echo ""
    echo "Shutting down phone localizer..."
    kill $PID_RTABMAP 2>/dev/null
    wait 2>/dev/null
    echo "Done."
}
trap cleanup EXIT INT TERM

echo "=== Starting phone localizer (RTAB-Map localization mode) ==="
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
PID_RTABMAP=$!

echo ""
echo "=== Phone localizer running ==="
echo "  Subscribes to: /phone/image/compressed, /phone/camera_info"
echo "  Publishes to:  /phone/pose"
echo "  Map database:  $DB_PATH"
echo "  Press Ctrl+C to stop"
echo ""

wait $PID_RTABMAP
