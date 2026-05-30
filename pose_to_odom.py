#!/usr/bin/env python3
"""Converts /phone/pose (PoseStamped) to Odometry + TF for RTAB-Map."""

import rclpy
from rclpy.node import Node
from geometry_msgs.msg import PoseStamped
from nav_msgs.msg import Odometry
from tf2_ros import TransformBroadcaster
from geometry_msgs.msg import TransformStamped


class PoseToOdom(Node):
    def __init__(self):
        super().__init__('pose_to_odom')
        self.sub = self.create_subscription(PoseStamped, '/phone/pose', self.cb, 10)
        self.pub = self.create_publisher(Odometry, '/phone_map/odom', 10)
        self.tf_br = TransformBroadcaster(self)
        self.get_logger().info('pose_to_odom ready')

    def cb(self, msg: PoseStamped):
        # Odometry
        odom = Odometry()
        odom.header = msg.header
        odom.header.frame_id = 'phone_odom'
        odom.child_frame_id = 'phone_camera'
        odom.pose.pose = msg.pose
        # Small diagonal covariance (ARCore is fairly accurate)
        for i in range(6):
            odom.pose.covariance[i * 7] = 0.01
        self.pub.publish(odom)

        # TF: phone_odom → phone_camera
        t = TransformStamped()
        t.header = odom.header
        t.child_frame_id = 'phone_camera'
        t.transform.translation.x = msg.pose.position.x
        t.transform.translation.y = msg.pose.position.y
        t.transform.translation.z = msg.pose.position.z
        t.transform.rotation = msg.pose.orientation
        self.tf_br.sendTransform(t)


def main():
    rclpy.init()
    rclpy.spin(PoseToOdom())
    rclpy.shutdown()


if __name__ == '__main__':
    main()
