# 006 — Glasses Depth Sensor Selection

## Decision

Leading candidate: **ams TMF8821** for glasses-side depth sensing. Optimized for range and speed over FOV — the user's head provides the sweep.

## Context

The glasses need a depth sensor for two functions:
1. OOBE room scanning (decision 005) — stream depth to hub for map building
2. Near-field interaction (phase 3) — hands, held objects

Key constraint: maximize range to reduce OOBE scan time and eliminate "walk near every wall" requirement. Speed must stay high for fast head motion. FOV is negotiable — head rotation is the gimbal.

## Leading Candidate

### ams TMF8821
- **Range**: 5m
- **FOV**: ~33° (3x3 / 4x4 multizone)
- **Speed**: 30Hz
- **Size**: 4.6 x 2.5 x 2.0 mm
- **Weight**: <1g
- **Unit price**: ~$5 (single unit), ~$3.50 (1000+)
- **Why**: Best range-to-weight ratio in this class. Narrow FOV concentrates laser power for longer range. 30Hz at typical head rotation (~60°/sec) captures a frame every ~2° — dense enough for hub stitching. Direct ToF (dToF) handles ambient light well.
- **Dev board**: TMF8821-EVM available

## Runner-Ups

### ams TMF8828
- **Range**: 5m
- **FOV**: 63° (configurable up to 8x8 multizone)
- **Speed**: 30Hz
- **Size**: 4.6 x 2.5 x 2.0 mm
- **Weight**: <1g
- **Unit price**: ~$9.50 (single), ~$4.20 (500+)
- **Why consider**: Same range as TMF8821 but wider FOV and higher resolution (8x8). If near-field hand tracking needs wider coverage, this is the upgrade path. Same package — drop-in swap.
- **Trade-off**: Wider FOV dilutes laser power; 5m range may degrade at edges. Higher cost.

### ST VL53L8CX
- **Range**: 4m
- **FOV**: 63°
- **Speed**: 60Hz
- **Size**: 6.4 x 3.0 x 1.5 mm
- **Weight**: <1g
- **Unit price**: ~$3-5 (estimated, check DigiKey/Mouser)
- **Why consider**: Fastest in class at 60Hz. Best motion tolerance. Most community/hobbyist support. Well-documented.
- **Trade-off**: 1m less range than TMF8821. Indirect ToF (iToF) — more susceptible to ambient light than dToF sensors.
- **Dev board**: SATEL-VL53L8 (~$17), P-NUCLEO-53L8A1 kit available

### ST VL53L8CH
- **Range**: 4m
- **FOV**: 65°
- **Speed**: 60Hz
- **Size**: 6.4 x 3.0 x 1.5 mm
- **Weight**: <1g
- **Unit price**: ~$3-5 (estimated)
- **Why consider**: Latest ST generation. Outputs histogram data designed for AI processing — could feed directly into neural inference on glasses silicon (phase 7). Best ambient light performance in the ST lineup.
- **Trade-off**: Same 4m range limitation. Newer, less community support.

### Infineon REAL3 IRS2877C
- **Range**: 5-8m (with VCSEL illuminator)
- **FOV**: Configurable
- **Speed**: 30-60Hz
- **Resolution**: HVGA (~153k pixels) — orders of magnitude more than 8x8
- **Size**: 4.4 x 5.1 mm (sensor only, needs illuminator module)
- **Weight**: ~1-2g (sensor), ~3-5g (full module estimated)
- **Unit price**: Contact Infineon (not commodity-priced, likely $15-30+ for module)
- **Why consider**: Dramatically higher resolution depth map. Real depth imaging, not just zone ranging. Could enable rich 3D reconstruction during OOBE and better hand/object tracking. Longest range potential.
- **Trade-off**: Needs separate VCSEL illuminator and driver IC — more board space, more power, more weight. Not a single-chip solution. Higher integration complexity. Likely higher cost. Adds 3-5g to glasses weight budget.
- **When it makes sense**: If the 8x8 grid from TMF8821 proves too low-resolution for quality room scanning or hand tracking. The jump from 64 zones to 153k pixels is massive.

## Comparison Summary

| Sensor | Range | FOV | Speed | Weight | Price (1x) | Resolution | Best For |
|---|---|---|---|---|---|---|---|
| **TMF8821** | 5m | 33° | 30Hz | <1g | ~$5 | 3x3/4x4 | OOBE scanning (range priority) |
| TMF8828 | 5m | 63° | 30Hz | <1g | ~$10 | 8x8 | Near-field + scanning |
| VL53L8CX | 4m | 63° | 60Hz | <1g | ~$4 | 8x8 | Fast motion tolerance |
| VL53L8CH | 4m | 65° | 60Hz | <1g | ~$4 | 8x8 | AI histogram output |
| IRS2877C | 5-8m | varies | 30-60Hz | ~3-5g | ~$15-30 | 153k px | High-res 3D reconstruction |

## Decision Rationale

The TMF8821 wins on the primary optimization axis: maximum range at minimum weight. The 33° FOV is a feature, not a limitation — it concentrates power for range and the head provides the sweep.

The Infineon IRS2877C remains interesting as a phase 7 upgrade if higher-resolution depth proves necessary for hand tracking or richer room scans. The weight penalty (~3-5g for full module) is manageable but should be validated against the total glasses weight budget.

## Open Questions

- Does 3x3/4x4 zone resolution from TMF8821 provide enough density for the hub to build a quality room map during OOBE?
- Can TMF8828 (8x8, same range) serve as a middle ground if TMF8821 is too sparse?
- What is the actual module weight for IRS2877C + VCSEL illuminator in a glasses form factor?

## Status

TMF8821 selected as leading candidate. Final validation pending prototype testing.
