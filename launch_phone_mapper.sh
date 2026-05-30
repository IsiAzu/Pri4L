#!/bin/bash
# Phone Mapper — Launch Script
# Starts pose_to_odom, decompress_image, and RTAB-Map (RGB-only, no visual odom)
# to build a second map database from phone camera frames.

set -e

LOG_DIR="${HOME}/.ros/logs/phone_mapper"
mkdir -p "$LOG_DIR"

SESSION_LOG="$LOG_DIR/session_$(date +%Y%m%d_%H%M%S).log"
ln -sf "$SESSION_LOG" "$LOG_DIR/latest.log"

log_pipe() {
    local tag="$1"
    while IFS= read -r line; do
        clean=$(echo "$line" | sed 's/\x1b\[[0-9;]*m//g')
        echo "[$(date '+%H:%M:%S')] [$tag] $clean" >> "$SESSION_LOG"
    done
}

source /opt/ros/humble/setup.bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PIDS=()

cleanup() {
    echo ""
    echo "[$(date '+%H:%M:%S')] [phone_mapper] Shutting down (Ctrl+C)" >> "$SESSION_LOG"
    echo "Shutting down..."
    kill "${PIDS[@]}" 2>/dev/null
    wait 2>/dev/null
    echo "[$(date '+%H:%M:%S')] [phone_mapper] All processes stopped" >> "$SESSION_LOG"
    echo "Done. Session log: $SESSION_LOG"
}
trap cleanup EXIT INT TERM

cat >> "$SESSION_LOG" <<EOF
================================================================================
Phone mapper session started: $(date '+%Y-%m-%d %H:%M:%S')
Host: $(hostname) ($(hostname -I | awk '{print $1}'))
Phone map database: ~/.ros/rtabmap_phone_map.db
================================================================================
EOF

# 1. Pose-to-Odometry converter
echo "=== Starting pose_to_odom ==="
echo "[$(date '+%H:%M:%S')] [phone_mapper] Starting pose_to_odom" >> "$SESSION_LOG"
python3 "$SCRIPT_DIR/pose_to_odom.py" 2>&1 | log_pipe "pose_to_odom" &
PIDS+=($!)

# 2. Image decompressor
echo "=== Starting decompress_image ==="
echo "[$(date '+%H:%M:%S')] [phone_mapper] Starting decompress_image" >> "$SESSION_LOG"
python3 "$SCRIPT_DIR/decompress_image.py" 2>&1 | log_pipe "decompress" &
PIDS+=($!)

sleep 2

echo ""
echo "=== Phone mapper running ==="
echo "  Phone map: ~/.ros/rtabmap_phone_map.db"
echo "  Session log: $SESSION_LOG"
echo "  Latest log:  $LOG_DIR/latest.log"
echo "  Press Ctrl+C to stop"
echo ""

# 3. RTAB-Map (RGB-only, ARCore odom, foreground)
echo "=== Starting RTAB-Map (phone_map) ==="
echo "[$(date '+%H:%M:%S')] [phone_mapper] Starting RTAB-Map (phone_map)" >> "$SESSION_LOG"
ros2 launch rtabmap_launch rtabmap.launch.py \
    namespace:=phone_map \
    rgb_topic:=/phone_map/image_raw \
    camera_info_topic:=/phone/camera_info \
    depth:=false \
    subscribe_rgb:=true \
    visual_odometry:=false \
    odom_topic:=/phone_map/odom \
    frame_id:=phone_camera \
    odom_frame_id:=phone_odom \
    approx_sync:=true \
    database_path:=${HOME}/.ros/rtabmap_phone_map.db \
    publish_tf_map:=false \
    'args:=--RGBD/CreateOccupancyGrid false --Vis/MinInliers 10' 2>&1 | log_pipe "phone_map"
