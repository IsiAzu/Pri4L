#!/usr/bin/env python3
"""Publish random anchors within the D435 depth frustum to /hub/anchors at 1Hz.

Coordinate convention (ROS standard): x-forward, y-left, z-up.
D435 FOV: ~87° horizontal, ~58° vertical.
"""

import argparse
import math
import random
import subprocess
import sys

HALF_H_FOV = math.radians(43.5)  # half of 87° horizontal
HALF_V_FOV = math.radians(29.0)  # half of 58° vertical
MARGIN = 0.8  # keep cubes away from frustum edges


def generate_points(count: int, seed: int | None = None) -> list[tuple[float, float, float]]:
    if seed is not None:
        random.seed(seed)
    points = []
    for _ in range(count):
        d = random.uniform(0.5, 2.5)
        y = random.uniform(-1, 1) * d * math.tan(HALF_H_FOV) * MARGIN
        z = random.uniform(-1, 1) * d * math.tan(HALF_V_FOV) * MARGIN
        points.append((round(d, 3), round(y, 3), round(z, 3)))
    return points


def build_pose_array_yaml(points: list[tuple[float, float, float]]) -> str:
    poses = []
    for x, y, z in points:
        poses.append(
            f"    {{\n"
            f"      position: {{x: {x}, y: {y}, z: {z}}},\n"
            f"      orientation: {{x: 0.0, y: 0.0, z: 0.0, w: 1.0}}\n"
            f"    }}"
        )
    return (
        "{\n"
        "  header: {\n"
        "    frame_id: 'map'\n"
        "  },\n"
        "  poses: [\n"
        + ",\n".join(poses) + "\n"
        "  ]\n"
        "}"
    )


def main():
    parser = argparse.ArgumentParser(description="Publish random anchors in D435 FOV")
    parser.add_argument("--count", type=int, default=5, help="Number of anchors (default: 5)")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    args = parser.parse_args()

    points = generate_points(args.count, args.seed)

    print(f"=== Publishing {args.count} random anchors to /hub/anchors at 1Hz ===")
    for i, (x, y, z) in enumerate(points):
        print(f"  Anchor {i+1}: ({x}, {y}, {z})")
    print("  Press Ctrl+C to stop")
    print()

    yaml_msg = build_pose_array_yaml(points)

    try:
        subprocess.run(
            [
                "ros2", "topic", "pub",
                "/hub/anchors", "geometry_msgs/msg/PoseArray",
                yaml_msg,
                "--rate", "1",
            ],
            check=True,
        )
    except KeyboardInterrupt:
        print("\nStopped.")
    except FileNotFoundError:
        print("Error: ros2 not found. Source your ROS2 setup first.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
