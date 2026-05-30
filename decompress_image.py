#!/usr/bin/env python3
"""Decodes /phone/image/compressed (CompressedImage) to raw Image for RTAB-Map."""

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import CompressedImage, Image
from cv_bridge import CvBridge
import cv2
import numpy as np


class DecompressImage(Node):
    def __init__(self):
        super().__init__('decompress_image')
        self.sub = self.create_subscription(
            CompressedImage, '/phone/image/compressed', self.cb, 10)
        self.pub = self.create_publisher(Image, '/phone_map/image_raw', 10)
        self.bridge = CvBridge()
        self.get_logger().info('decompress_image ready')

    def cb(self, msg: CompressedImage):
        np_arr = np.frombuffer(msg.data, np.uint8)
        cv_img = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if cv_img is None:
            return
        img_msg = self.bridge.cv2_to_imgmsg(cv_img, encoding='bgr8')
        img_msg.header = msg.header
        self.pub.publish(img_msg)


def main():
    rclpy.init()
    rclpy.spin(DecompressImage())
    rclpy.shutdown()


if __name__ == '__main__':
    main()
