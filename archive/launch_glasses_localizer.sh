#!/bin/bash
# AR Spatial Hub — Glasses Localizer (EXPERIMENT)
# Second RTAB-Map instance in localization-only mode that tries to relocalize the INMO
# glasses' world camera against the hub's saved map (cross-sensor visual matching).
#
# This revisits the approach decision 009 abandoned for the phone (3-5/6 inliers). We're
# running it to see the glasses-cam numbers firsthand before committing to fiducial align.
#
# Prereqs: hub running (rosbridge + map at ~/.ros/rtabmap.db), glasses app streaming
#          /glasses/image + /glasses/camera_info.
# Output:  /glasses/pose (if it ever localizes). Watch the log for loop-closure inliers.

set -e
source /opt/ros/humble/setup.bash

DB_PATH="${HOME}/.ros/rtabmap.db"
LOG="${HOME}/.ros/logs/hub/glasses_localizer.log"
mkdir -p "$(dirname "$LOG")"

if [ ! -f "$DB_PATH" ]; then
    echo "ERROR: No map database at $DB_PATH — run launch_hub.sh first."
    exit 1
fi

cleanup() { echo "Shutting down glasses localizer..."; kill $PID 2>/dev/null; wait 2>/dev/null; }
trap cleanup EXIT INT TERM

echo "=== Glasses localizer (RTAB-Map localization mode) ==="
echo "  rgb=/glasses/image  info=/glasses/camera_info  db=$DB_PATH  log=$LOG"

# Localization-only, monocular (no depth). Mirrors the removed phone localizer (decision 009)
# but pointed at /glasses/* and with looser visual-matching gates so we can SEE inlier counts:
#   Vis/MinInliers low + RGBD/OptimizeMaxError 0 so matches aren't silently rejected on odom.
# Run the rtabmap node DIRECTLY in RGB-only mode (the rtabmap_launch wrapper always wires an
# RGB-D sync and hangs waiting on a depth topic the monocular glasses cam can't provide).
#   subscribe_rgb + subscribe_depth=false  -> RGB + camera_info only
#   Mem/IncrementalMemory=false            -> localization (don't grow the map)
#   Vis/EstimationType=1 (PnP)             -> 2D(query)->3D(map) pose, the only option w/o depth
#   Kp/DetectorStrategy / Vis/FeatureType  -> SIFT (best cross-sensor descriptor per decision 009)
#   Vis/MinInliers 6                       -> the 6-inlier gate 009 couldn't clear; we LOG attempts
#   odom:=/glasses/odom (dummy identity)   -> gives rtabmap a pose source so it processes frames
# NB: RTAB-Map's "Foo/Bar" params must be passed as STRINGS (the node rejects int/bool/float).
# The :="'value'" quoting makes ros2/YAML treat them as strings. Node-level params
# (subscribe_*, approx_sync, frame_id, database_path) are real typed params.
ros2 run rtabmap_slam rtabmap --ros-args \
    -p subscribe_depth:=false \
    -p subscribe_rgb:=true \
    -p approx_sync:=true \
    -p frame_id:=glasses_camera \
    -p database_path:="$DB_PATH" \
    -p Mem/IncrementalMemory:="'false'" \
    -p Mem/InitWMWithAllNodes:="'true'" \
    -p Vis/EstimationType:="'1'" \
    -p Vis/MinInliers:="'6'" \
    -p RGBD/OptimizeMaxError:="'0.0'" \
    -p Rtabmap/LoopThr:="'0.05'" \
    -r rgb/image:=/glasses/image \
    -r rgb/camera_info:=/glasses/camera_info \
    -r odom:=/glasses/odom 2>&1 | tee "$LOG" &
PID=$!

echo "  PID=$PID — Ctrl+C to stop. Tail: tail -f $LOG"
wait $PID
