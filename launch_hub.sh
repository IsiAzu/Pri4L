#!/bin/bash
# AR Spatial Hub — Launch Script
# Starts RealSense, RTAB-Map, and rosbridge WebSocket
# All output is captured in a single timestamped session log.

set -e

LOG_DIR="${HOME}/.ros/logs/hub"
mkdir -p "$LOG_DIR"

# Session log: single file with everything, timestamped
SESSION_LOG="$LOG_DIR/session_$(date +%Y%m%d_%H%M%S).log"
# Also keep a symlink to the latest session
ln -sf "$SESSION_LOG" "$LOG_DIR/latest.log"

show_help() {
    cat <<'EOF'
Usage: bash launch_hub.sh [OPTIONS]

Starts the hub: RealSense sensor, RTAB-Map, and rosbridge WebSocket (port 9090).
Phone localization is handled by ARCore on the phone app (no hub-side processing needed).

Options:
  --new-map      Start a fresh map (deletes existing database)
  --localize     Relocalize against existing map (read-only)
  --merged       Localize against merged map (~/.ros/rtabmap_merged.db)
  --verbose      Also print all output to terminal (default: terminal is quiet)
  -h, --help     Show this help message

Session logs are saved to ~/.ros/logs/hub/session_<timestamp>.log
Latest session is always at ~/.ros/logs/hub/latest.log

Examples:
  bash launch_hub.sh                  # Resume mapping from existing database
  bash launch_hub.sh --new-map        # Start fresh
  bash launch_hub.sh --localize       # Lock map, localize only
  bash launch_hub.sh --merged         # Localize against merged map
EOF
    exit 0
}

# Logging helper: prefixes each line with timestamp and component tag
log_pipe() {
    local tag="$1"
    while IFS= read -r line; do
        # Strip ANSI escape codes for cleaner logs
        clean=$(echo "$line" | sed 's/\x1b\[[0-9;]*m//g')
        echo "[$(date '+%H:%M:%S')] [$tag] $clean" >> "$SESSION_LOG"
    done
}

source /opt/ros/humble/setup.bash

RTABMAP_EXTRA=""
LOCALIZATION="false"
VERBOSE="false"
DATABASE_PATH=""

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
        --merged)
            DATABASE_PATH="${HOME}/.ros/rtabmap_merged.db"
            LOCALIZATION="true"
            if [ ! -f "$DATABASE_PATH" ]; then
                echo "Error: Merged database not found at $DATABASE_PATH"
                echo "Run merge_maps.sh first to create it."
                exit 1
            fi
            echo "Localization mode (using merged map: $DATABASE_PATH)"
            ;;
        --verbose)
            VERBOSE="true"
            ;;
        -h|--help)
            show_help
            ;;
    esac
done

PIDS=()

cleanup() {
    echo ""
    echo "[$(date '+%H:%M:%S')] [hub] Shutting down (Ctrl+C)" >> "$SESSION_LOG"
    echo "Shutting down..."
    kill "${PIDS[@]}" 2>/dev/null
    wait 2>/dev/null
    echo "[$(date '+%H:%M:%S')] [hub] All processes stopped" >> "$SESSION_LOG"
    echo "Done. Session log: $SESSION_LOG"
}
trap cleanup EXIT INT TERM

# Write session header
cat >> "$SESSION_LOG" <<EOF
================================================================================
Hub session started: $(date '+%Y-%m-%d %H:%M:%S')
Arguments: $@
Host: $(hostname) ($(hostname -I | awk '{print $1}'))
Map database: ${DATABASE_PATH:-~/.ros/rtabmap.db} ($(ls -lh "${DATABASE_PATH:-$HOME/.ros/rtabmap.db}" 2>/dev/null | awk '{print $5}' || echo 'not found'))
================================================================================
EOF

# 1. RealSense
echo "=== Starting RealSense ==="
echo "[$(date '+%H:%M:%S')] [hub] Starting RealSense" >> "$SESSION_LOG"
ros2 launch realsense2_camera rs_launch.py \
    rgb_camera.color_profile:=1280x720x30 \
    depth_module.depth_profile:=1280x720x30 \
    align_depth.enable:=true \
    enable_sync:=true 2>&1 | log_pipe "realsense" &
PIDS+=($!)

sleep 3

# 2. rosbridge WebSocket (port 9090)
echo "=== Starting rosbridge WebSocket ==="
echo "[$(date '+%H:%M:%S')] [hub] Starting rosbridge WebSocket" >> "$SESSION_LOG"
ros2 launch rosbridge_server rosbridge_websocket_launch.xml 2>&1 | log_pipe "rosbridge" &
PIDS+=($!)

sleep 2

echo ""
echo "=== Hub running ==="
echo "  WebSocket: ws://$(hostname -I | awk '{print $1}'):9090"
echo "  Map database: ${DATABASE_PATH:-~/.ros/rtabmap.db}"
echo "  Phone: use ARCore app, tap Align at the D435"
echo "  Session log: $SESSION_LOG"
echo "  Latest log:  $LOG_DIR/latest.log"
echo "  Press Ctrl+C to stop"
echo ""

# 3. RTAB-Map (foreground process — keeps script alive)
echo "=== Starting RTAB-Map ==="
echo "[$(date '+%H:%M:%S')] [hub] Starting RTAB-Map (foreground)" >> "$SESSION_LOG"
DB_PARAM=""
if [ -n "$DATABASE_PATH" ]; then
    DB_PARAM="database_path:=$DATABASE_PATH"
fi
if [ "$VERBOSE" = "true" ]; then
    ros2 launch rtabmap_launch rtabmap.launch.py \
        $RTABMAP_EXTRA \
        $DB_PARAM \
        localization:=$LOCALIZATION \
        rgb_topic:=/camera/camera/color/image_raw \
        depth_topic:=/camera/camera/depth/image_rect_raw \
        camera_info_topic:=/camera/camera/color/camera_info \
        frame_id:=camera_link \
        approx_sync:=true \
        'args:=--Vis/MinInliers 10' 2>&1 | tee >(log_pipe "rtabmap")
else
    ros2 launch rtabmap_launch rtabmap.launch.py \
        $RTABMAP_EXTRA \
        $DB_PARAM \
        localization:=$LOCALIZATION \
        rgb_topic:=/camera/camera/color/image_raw \
        depth_topic:=/camera/camera/depth/image_rect_raw \
        camera_info_topic:=/camera/camera/color/camera_info \
        frame_id:=camera_link \
        approx_sync:=true \
        'args:=--Vis/MinInliers 10' 2>&1 | log_pipe "rtabmap"
fi
