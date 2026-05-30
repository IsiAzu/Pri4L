#!/bin/bash
# Merge hub D435 map and phone map into a single database.
# Run this after both launch_hub.sh and launch_phone_mapper.sh have stopped.

set -e

source /opt/ros/humble/setup.bash

HUB_DB="${HOME}/.ros/rtabmap.db"
PHONE_DB="${HOME}/.ros/rtabmap_phone_map.db"
MERGED_DB="${HOME}/.ros/rtabmap_merged.db"

LOG_DIR="${HOME}/.ros/logs/phone_mapper"
mkdir -p "$LOG_DIR"
MERGE_LOG="$LOG_DIR/merge_$(date +%Y%m%d_%H%M%S).log"

for db in "$HUB_DB" "$PHONE_DB"; do
    if [ ! -f "$db" ]; then
        echo "Error: $db not found. Run both mappers first."
        exit 1
    fi
done

if [ -f "$MERGED_DB" ]; then
    echo "Existing merged database found. Backing up..."
    mv "$MERGED_DB" "${MERGED_DB}.bak_$(date +%Y%m%d_%H%M%S)"
fi

echo "Merging maps..."
echo "  Hub:    $HUB_DB ($(ls -lh "$HUB_DB" | awk '{print $5}'))"
echo "  Phone:  $PHONE_DB ($(ls -lh "$PHONE_DB" | awk '{print $5}'))"
echo "  Output: $MERGED_DB"
echo "  Log:    $MERGE_LOG"
echo ""

rtabmap-reprocess "${HUB_DB};${PHONE_DB}" "$MERGED_DB" 2>&1 | tee "$MERGE_LOG"

echo ""
echo "Merge complete: $MERGED_DB"
echo "Check for inter-map loop closures:"
echo "  grep -i 'loop closure' $MERGE_LOG | tail -10"
