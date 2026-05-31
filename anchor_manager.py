#!/usr/bin/env python3
"""Anchor Manager — stores and serves spatial anchors.

Accepts anchor creation from hub or phone via separate topics.
Publishes per-source PoseArrays so clients can color-code.
Persists anchors to a JSON file so they survive restarts.
"""

import json
import os
import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Pose, PoseArray, PoseStamped
from std_msgs.msg import Header


ANCHORS_FILE = os.path.expanduser("~/.ros/anchors.json")


class AnchorManager(Node):
    def __init__(self):
        super().__init__('anchor_manager')
        self.anchors: list[dict] = self.load_anchors()

        # Accept new anchors from hub or phone
        self.create_hub_sub = self.create_subscription(
            PoseStamped, '/hub/anchors/create', self.on_create_hub, 10)
        self.create_phone_sub = self.create_subscription(
            PoseStamped, '/phone/anchors/create', self.on_create_phone, 10)

        # Publish per-source lists at 1Hz
        self.pub_hub = self.create_publisher(PoseArray, '/hub/anchors/hub', 10)
        self.pub_phone = self.create_publisher(PoseArray, '/hub/anchors/phone', 10)
        # Combined (backward compat)
        self.pub_all = self.create_publisher(PoseArray, '/hub/anchors', 10)
        self.timer = self.create_timer(1.0, self.publish_anchors)

        self.get_logger().info(
            f'anchor_manager ready — {len(self.anchors)} anchors loaded from {ANCHORS_FILE}')

    def load_anchors(self) -> list[dict]:
        if os.path.exists(ANCHORS_FILE):
            try:
                with open(ANCHORS_FILE, 'r') as f:
                    return json.load(f)
            except (json.JSONDecodeError, IOError):
                pass
        return []

    def save_anchors(self):
        os.makedirs(os.path.dirname(ANCHORS_FILE), exist_ok=True)
        with open(ANCHORS_FILE, 'w') as f:
            json.dump(self.anchors, f, indent=2)

    def on_create_hub(self, msg: PoseStamped):
        self._create_anchor(msg, 'hub')

    def on_create_phone(self, msg: PoseStamped):
        self._create_anchor(msg, 'phone')

    def _create_anchor(self, msg: PoseStamped, source: str):
        p = msg.pose.position
        o = msg.pose.orientation
        anchor = {
            'position': {'x': p.x, 'y': p.y, 'z': p.z},
            'orientation': {'x': o.x, 'y': o.y, 'z': o.z, 'w': o.w},
            'source': source,
        }
        self.anchors.append(anchor)
        self.save_anchors()
        self.get_logger().info(
            f'[{source}] Anchor created at ({p.x:.3f}, {p.y:.3f}, {p.z:.3f}) — '
            f'{len(self.anchors)} total')

    def _build_pose_array(self, anchors: list[dict]) -> PoseArray:
        msg = PoseArray()
        msg.header = Header()
        msg.header.frame_id = 'map'
        msg.header.stamp = self.get_clock().now().to_msg()
        for a in anchors:
            pose = Pose()
            pos = a['position']
            ori = a['orientation']
            pose.position.x = pos['x']
            pose.position.y = pos['y']
            pose.position.z = pos['z']
            pose.orientation.x = ori['x']
            pose.orientation.y = ori['y']
            pose.orientation.z = ori['z']
            pose.orientation.w = ori['w']
            msg.poses.append(pose)
        return msg

    def publish_anchors(self):
        hub_anchors = [a for a in self.anchors if a.get('source') == 'hub']
        phone_anchors = [a for a in self.anchors if a.get('source') == 'phone']

        self.pub_hub.publish(self._build_pose_array(hub_anchors))
        self.pub_phone.publish(self._build_pose_array(phone_anchors))
        self.pub_all.publish(self._build_pose_array(self.anchors))


def main():
    rclpy.init()
    node = AnchorManager()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    node.destroy_node()
    rclpy.shutdown()


if __name__ == '__main__':
    main()
