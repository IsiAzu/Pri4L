# Archive

Experiments and one-off artifacts kept for reference but not part of the active system.

## `launch_glasses_localizer.sh`

A second RTAB-Map instance in RGB-only localization mode that tried to relocalize the INMO
glasses' world camera against the hub's RealSense-built map (cross-sensor visual matching).

**Outcome (2026-06-23): dead end — archived, do not revive.** It reproduced the failure
decision 009 already documented for the phone: cross-sensor monocular relocalization can't
register frames to the RGB-D map (0 successful localizations, 84 `Transform cannot be
estimated`). The working path for client→hub alignment is **fiducial alignment** (decision
010): see `fiducials/aruco_DICT4X4_50_id0.png` and the `FiducialAligner` in the apps.
