# 005 — Glasses as OOBE Room Scanner

## Decision

Use the glasses as a roving depth sensor during first-time setup (OOBE) to build a complete room map, rather than purchasing a dedicated 360° lidar for the hub.

## Context

The hub's fixed D435i has an 87° horizontal FOV — it can only map what it sees from its mounting position. Full room coverage traditionally requires a 360° lidar (Livox Mid-360 ~$800, Ouster OSDome ~$2,500+), adding cost and complexity.

The glasses already carry an RGB camera and will carry a near-field depth sensor (phase 3). During OOBE, the user naturally walks through the room — the glasses can stream RGB + depth back to the hub, which fuses it into the world model.

## Flow

1. Hub D435i does initial partial scan from fixed position
2. OOBE: user walks room wearing glasses, glasses stream RGB + depth to hub
3. Hub fuses glasses data into existing map, fills coverage gaps
4. After OOBE, hub has complete room map; glasses revert to thin display client

## Tradeoffs

- **Pro**: Eliminates or defers need for expensive 360° sensor
- **Pro**: Uses hardware already required for other functions (glasses depth sensor)
- **Pro**: Consistent with hub-owns-world-model architecture — glasses are just a data source
- **Con**: Scan quality depends on user behavior (speed, coverage, missed areas)
- **Con**: Requires glasses-to-hub streaming pipeline (phase 2) before this works
- **Con**: Near-field ToF range (~2-4m for VL53 series) limits per-frame coverage; large rooms need multiple passes

## Dependencies

- Phase 2: Client-to-hub streaming pipeline
- Phase 3: Glasses-side depth sensor

## Status

Accepted — 360° hub sensor deferred to optional upgrade
