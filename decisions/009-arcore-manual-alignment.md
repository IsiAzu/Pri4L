# Decision 009: ARCore with Manual Alignment for Phone Localization

**Status:** Accepted
**Date:** 2026-03-15

## Context

The phone needs to know its position in the hub's RTAB-Map coordinate frame so it can render AR overlays (anchors, spatial content) at the correct world positions.

The original approach used a second RTAB-Map instance on the hub to relocalize the phone's camera against the hub's map via cross-sensor visual feature matching (phone camera vs RealSense D435). This failed because:

1. **Viewpoint mismatch**: A fixed D435 in a corner captures the room from one angle. The phone is held from completely different positions/orientations. Feature overlap is inherently small.
2. **Cross-sensor feature matching**: Different cameras produce different feature descriptors. Even with SIFT and resolution bumps (320x240 -> 640x480 on phone, default -> 1280x720 on D435), we only achieved 3-5 inliers vs the 6 minimum required.
3. **No real odometry**: The phone had no odometry source, so RTAB-Map's sanity checks (transform distance, optimization error) rejected valid matches.

## Decision

Use **ARCore** on the phone for 6DOF visual-inertial tracking, with a **one-time manual alignment** to link the ARCore coordinate frame to the hub's RTAB-Map frame.

### Alignment flow

1. User starts AR mode in the phone app
2. User stands at the D435, pointing the phone in the same direction the D435 faces
3. User taps "Align"
4. The app captures the ARCore pose at that moment and pairs it with the D435's known position in the hub frame (origin with identity rotation)
5. A rigid transform (4x4 matrix) is computed between the two coordinate frames
6. All subsequent ARCore poses are converted to hub coordinates using this transform

### What this eliminates

- The second RTAB-Map instance on the hub (`--with-phone` flag, phone localizer)
- Cross-sensor feature matching entirely
- Phone image streaming for relocalization (still available for future use)
- The `launch_phone_localizer.sh` script

### What the phone publishes after alignment

- `/phone/pose` (geometry_msgs/PoseStamped) — phone position in hub/map frame at ~10Hz
- `/phone/image/compressed` — camera frames (for future use)
- `/phone/camera_info` — intrinsics from ARCore

## Alternatives Considered

### AprilTag alignment (recommended upgrade path)

Print an AprilTag and mount it at a known position relative to the D435. Both the D435 and phone can detect it independently and compute poses relative to the tag.

**Pros:** Fully automatic (no user action), sub-centimeter accuracy, works from any position in the room as long as the tag is visible.
**Cons:** Requires printing/mounting a tag, adds AprilTag detection library (OpenCV + apriltag), tag must be visible during alignment, lighting-dependent.

This is the best upgrade if manual alignment proves annoying or insufficiently accurate.

### QR code alignment

Similar to AprilTag but using a QR code displayed on a screen or printed.

**Pros:** Easy to generate, can encode hub IP/config.
**Cons:** QR pose estimation is less accurate than AprilTag (corners less distinct), typically only gives position not full 6DOF orientation. Not recommended for pose alignment.

## Accuracy

Manual alignment introduces error proportional to how precisely the user positions themselves at the D435 and matches its orientation. Expected accuracy:

- Position: ~5-10cm (arm's length from D435)
- Rotation: ~5-10 degrees (pointing "roughly" the same way)

This is acceptable for rendering 5cm AR cubes at hub-defined positions. For higher precision, upgrade to AprilTag alignment.

## Consequences

- Phone tracking is now entirely client-side (ARCore), reducing hub compute load
- Alignment only needs to happen once per AR session
- Hub launch script is simpler (no phone-specific processes)
- Works from any position/angle in the room after alignment (ARCore handles all tracking)
