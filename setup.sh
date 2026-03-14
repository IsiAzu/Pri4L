#!/bin/bash
# AR Spatial Hub — Full Setup Script
# Installs RealSense SDK, ROS2 Humble, and RTAB-Map on Ubuntu 22.04
# Run with: bash ar-spatial-hub-setup.sh

set -e

echo "=== AR Spatial Hub Setup ==="
echo "Target: Ubuntu 22.04 (Jammy)"
echo ""

# Check Ubuntu version
if [ "$(lsb_release -cs)" != "jammy" ]; then
    echo "Error: This script requires Ubuntu 22.04 (Jammy). Detected: $(lsb_release -cs)"
    exit 1
fi

# -----------------------------------------------
# 1. Intel RealSense SDK
# -----------------------------------------------
echo "=== Installing Intel RealSense SDK ==="

sudo mkdir -p /etc/apt/keyrings

curl -sSf https://librealsense.realsenseai.com/Debian/librealsense.pgp -o /tmp/librealsense.pgp
sudo cp /tmp/librealsense.pgp /etc/apt/keyrings/librealsenseai.gpg
rm /tmp/librealsense.pgp

echo "deb [signed-by=/etc/apt/keyrings/librealsenseai.gpg] https://librealsense.realsenseai.com/Debian/apt-repo jammy main" \
    | sudo tee /etc/apt/sources.list.d/librealsense.list > /dev/null

sudo apt update
sudo apt install -y librealsense2-dkms librealsense2-utils librealsense2-dev

echo "RealSense SDK installed. Verify with: realsense-viewer"
echo ""

# -----------------------------------------------
# 2. ROS2 Humble
# -----------------------------------------------
echo "=== Installing ROS2 Humble ==="

sudo curl -sSL https://raw.githubusercontent.com/ros/rosdistro/master/ros.key \
    -o /usr/share/keyrings/ros-archive-keyring.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/ros-archive-keyring.gpg] http://packages.ros.org/ros2/ubuntu jammy main" \
    | sudo tee /etc/apt/sources.list.d/ros2.list > /dev/null

sudo apt update
sudo apt install -y ros-humble-desktop

# Add to bashrc if not already present
if ! grep -q "source /opt/ros/humble/setup.bash" ~/.bashrc; then
    echo "source /opt/ros/humble/setup.bash" >> ~/.bashrc
fi

source /opt/ros/humble/setup.bash

echo "ROS2 Humble installed. Verify with: ros2 run demo_nodes_cpp talker"
echo ""

# -----------------------------------------------
# 3. RealSense ROS2 Wrapper
# -----------------------------------------------
echo "=== Installing RealSense ROS2 Wrapper ==="

sudo apt install -y ros-humble-realsense2-camera

echo "RealSense ROS2 wrapper installed."
echo "Verify with: ros2 launch realsense2_camera rs_launch.py"
echo ""

# -----------------------------------------------
# 4. RTAB-Map
# -----------------------------------------------
echo "=== Installing RTAB-Map ==="

sudo apt install -y ros-humble-rtabmap-ros

echo "RTAB-Map installed."
echo ""

# -----------------------------------------------
# Done
# -----------------------------------------------
echo "=== Setup Complete ==="
echo ""
echo "Quick start:"
echo "  Terminal 1: ros2 launch realsense2_camera rs_launch.py"
echo "  Terminal 2: ros2 launch rtabmap_launch rtabmap.launch.py \\"
echo "                rtabmap_args:=\"--delete_db_on_start\" \\"
echo "                rgb_topic:=/camera/camera/color/image_raw \\"
echo "                depth_topic:=/camera/camera/depth/image_rect_raw \\"
echo "                camera_info_topic:=/camera/camera/color/camera_info \\"
echo "                frame_id:=camera_link \\"
echo "                approx_sync:=true"
echo ""
echo "Relocalization (after building a map):"
echo "  ros2 launch rtabmap_launch rtabmap.launch.py \\"
echo "    localization:=true \\"
echo "    rgb_topic:=/camera/camera/color/image_raw \\"
echo "    depth_topic:=/camera/camera/depth/image_rect_raw \\"
echo "    camera_info_topic:=/camera/camera/color/camera_info \\"
echo "    frame_id:=camera_link \\"
echo "    approx_sync:=true"
echo ""
echo "Map database: ~/.ros/rtabmap.db"
