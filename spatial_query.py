#!/usr/bin/env python3
"""
AR Spatial Hub — Spatial Query Service

ROS2 node that accepts natural language questions about the room
and answers them using Ollama + spatial context from RTAB-Map.

Subscribes to:
  /rtabmap/mapData — current map graph (nodes, poses, labels)
  /rtabmap/odom    — current camera pose

Provides:
  /spatial_query (std_srvs/srv/SetBool) — temporary; will become custom service
  /hub/query topic — publish a String to ask a question, response on /hub/response

Publishes:
  /hub/response — LLM answer (std_msgs/String)

Uses Ollama HTTP API (localhost:11434) with llama3.2:3b.
"""

import json
import math
import urllib.request
import rclpy
from rclpy.node import Node
from std_msgs.msg import String
from nav_msgs.msg import Odometry
from geometry_msgs.msg import PoseStamped


class SpatialQueryNode(Node):

    def __init__(self):
        super().__init__('spatial_query')

        # Current spatial state
        self.camera_pose = None
        self.known_objects = []  # Will be populated by future object detection
        self.map_nodes = []     # Simplified map graph

        # Subscribers
        self.create_subscription(
            Odometry, '/rtabmap/odom', self.odom_callback, 10)
        self.create_subscription(
            String, '/hub/query', self.query_callback, 10)

        # Publisher
        self.response_pub = self.create_publisher(String, '/hub/response', 10)

        self.get_logger().info('Spatial query node started')
        self.get_logger().info('Publish to /hub/query to ask questions')
        self.get_logger().info('Responses on /hub/response')

    def odom_callback(self, msg):
        p = msg.pose.pose.position
        o = msg.pose.pose.orientation
        self.camera_pose = {
            'position': {'x': round(p.x, 3), 'y': round(p.y, 3), 'z': round(p.z, 3)},
            'orientation': {'x': round(o.x, 3), 'y': round(o.y, 3),
                           'z': round(o.z, 3), 'w': round(o.w, 3)}
        }

    def build_spatial_context(self):
        """Build a text description of what the hub knows about the room."""
        lines = []
        lines.append("You are an AI assistant embedded in a spatial computing hub.")
        lines.append("You have awareness of a physical room via a 3D map built by a depth camera.")
        lines.append("")

        if self.camera_pose:
            p = self.camera_pose['position']
            lines.append(f"The depth camera is currently at position: "
                        f"x={p['x']}m, y={p['y']}m, z={p['z']}m in the map frame.")
            lines.append("Coordinates: x=forward, y=left, z=up (ROS convention).")
        else:
            lines.append("The depth camera pose is not yet available.")

        lines.append("")
        lines.append(f"Map contains {len(self.map_nodes)} recorded positions.")

        if self.known_objects:
            lines.append("")
            lines.append("Known objects in the room:")
            for obj in self.known_objects:
                lines.append(f"  - {obj['label']} at ({obj['x']:.2f}, {obj['y']:.2f}, {obj['z']:.2f})")
        else:
            lines.append("")
            lines.append("No objects have been labeled yet. Object detection is not yet active.")
            lines.append("You can still answer questions about the spatial setup, "
                        "the hub architecture, and what capabilities are available.")

        return "\n".join(lines)

    def query_callback(self, msg):
        question = msg.data
        self.get_logger().info(f'Query: {question}')

        context = self.build_spatial_context()
        prompt = f"{context}\n\nUser question: {question}\n\nAnswer concisely."

        response = self.call_ollama(prompt)
        self.get_logger().info(f'Response: {response}')

        out = String()
        out.data = response
        self.response_pub.publish(out)

    def call_ollama(self, prompt):
        """Call Ollama HTTP API."""
        payload = json.dumps({
            'model': 'llama3.2:3b',
            'prompt': prompt,
            'stream': False,
            'options': {
                'temperature': 0.3,
                'num_predict': 200,
            }
        }).encode('utf-8')

        req = urllib.request.Request(
            'http://localhost:11434/api/generate',
            data=payload,
            headers={'Content-Type': 'application/json'}
        )

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                result = json.loads(resp.read().decode('utf-8'))
                return result.get('response', 'No response from model.')
        except Exception as e:
            return f'Error calling Ollama: {e}'


def main():
    rclpy.init()
    node = SpatialQueryNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
