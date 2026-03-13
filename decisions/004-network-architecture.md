# 004 — Network Architecture for Hub-to-Glasses Link

**Status:** open — decision required before Phase 2 build
**Date:** 2026-03

---

## Context

The hub renders base frames and serves spatial anchor data to the glasses over a local network. The link between hub and glasses is latency-critical. Getting this wrong produces visible anchor drift, disorientation, or nausea. Getting it right is the difference between a demo and a product.

### The 15ms target

15ms motion-to-photon is the design target for believable AR. The key architectural insight that makes this achievable: **timewarp on the glasses decouples hub render latency from perceived head-tracking latency.**

The glasses-side pipeline (IMU → timewarp → display) runs independently of the hub and can hit ~8ms regardless of WiFi timing. Hub latency does not affect rotational tracking smoothness — it affects content freshness and positional accuracy only.

**Latency budget breakdown:**

| Stage | Budget | Owner |
|-------|--------|-------|
| IMU capture | 1ms | Glasses |
| Timewarp computation | 2ms | Glasses GPU |
| Display scanout | 5ms | Glasses display |
| **Rotation tracking total** | **~8ms** | Glasses only — hub not in path |
| WiFi RTT (hub to glasses) | <5ms | Network |
| Hub render + encode | <10ms | Hub |
| **Content staleness** | **~15ms** | Acceptable at POC scale |

### When content staleness becomes perceptible

This is not purely a function of distance — it is a function of anchor proximity and user movement type:

- **Static anchors, any scale:** barely perceptible even at 20ms staleness. The brain fills in static content.
- **Anchors within ~1.5m:** visible wobble above ~15ms staleness during head rotations above ~60°/s. The anchor appears to float or slide relative to its physical reference point.
- **Translational head movement (leaning forward/sideways):** NOT correctable by timewarp regardless of WiFi speed. Timewarp only corrects rotation. Positional parallax error requires glasses-side depth sensing (Phase 3) to fuse local geometry into the correction.

The practical implication: at desk scale, where the primary use case involves anchors close to physical objects within arm's reach, content staleness above 15ms will be perceptible during normal interaction. This makes the WiFi RTT target non-negotiable for the intended use case.

---

## The Decision: How to provision the hub-glasses network link

Three approaches with materially different product, UX, and support implications.

---

### Option A — Depend on user's existing WiFi

Use whatever router the user has. No additional hardware required.

**RTT reality on common home hardware:**
- Quiet home network, 5GHz, close proximity: 2–5ms. Workable.
- Dense apartment, congested 2.4GHz, cheap ISP router: 8–15ms with variance. Problematic.
- Consumer router with no QoS: unpredictable under load.

**Pros:**
- Zero additional BOM cost
- No setup friction
- Correct approach for POC validation

**Cons:**
- Experience quality is a function of infrastructure outside product control
- "Your WiFi is too slow" is an unsupportable failure mode at scale
- No guarantee of hitting 5ms RTT target
- No isolation from other household traffic

**Verdict:** Right for POC. Not a viable product architecture. Establishes a baseline and identifies where the network becomes the bottleneck.

---

### Option B — Bundle a dedicated AP with the hub

Hub ships with a small dedicated access point — WiFi 6 or WiFi 6E — that creates a private isolated network exclusively for the hub-glasses link. The user's existing router handles all other traffic. The AP plugs into the hub; the glasses join the hub's private SSID.

**RTT on a dedicated AP, 5GHz or 6GHz:**
- Realistically achievable: 1–3ms
- No contention from other devices
- Full control over channel selection, QoS, and encryption

**Why 6GHz matters here:**
- 6GHz band has no legacy devices competing for spectrum
- No interference from neighbors' 2.4GHz or 5GHz networks
- Dedicated spectrum = predictable, low-variance RTT
- Critical in dense urban environments (apartments, offices) where 5GHz is saturated

**Product framing:**
This is architecturally correct for a fixed-location hub. The hub is an appliance — it owns its environment. Shipping the AP reframes the network as part of the product stack rather than a dependency on user infrastructure. Similar to how smart home hubs ship with their own Zigbee or Z-Wave radio rather than depending on the user's WiFi.

**Pros:**
- Owns the full latency stack end-to-end
- Consistent, measurable RTT regardless of user environment
- Eliminates the "your WiFi" support problem
- Enables QoS priority for the render stream
- Scales to enterprise (multiple glasses on one AP, shared hub)

**Cons:**
- Adds ~$40–80 to BOM (not a concern for this POC)
- Adds one more device to the physical setup
- Requires hub to run a DHCP server or NAT for the private network

**Verdict:** Strongest product answer for consistent experience. Recommended for Phase 2+ builds once POC validates the architecture.

---

### Option C — WiFi Direct (P2P, no router in path)

Glasses connect directly to the hub using WiFi Direct — a device-to-device protocol that requires no router at all. The connection is established peer-to-peer, with one device acting as a software access point.

**RTT on WiFi Direct:**
- Physically limited only by device proximity and radio quality
- No router hop = lowest possible latency
- Realistic RTT: 1–3ms

**Pros:**
- Zero infrastructure dependency — works anywhere
- Lowest possible RTT of the three options
- No AP to manage or configure
- Eliminates the router entirely from the latency equation

**Cons:**
- WiFi Direct support on Android (INMO Air3) is available but can be unreliable across manufacturers
- Throughput can be lower than infrastructure AP at longer range
- Complicates multi-glasses scenarios (one hub serving multiple pairs)
- Less control over channel and interference

**Verdict:** Worth investigating as a Phase 2 experiment. If the INMO Air3 supports stable WiFi Direct, this may be the cleanest single-user architecture. Degrades for multi-user / enterprise scenarios where Option B becomes necessary.

---

## Comparison

| | Option A | Option B | Option C |
|--|---------|---------|---------|
| RTT (typical) | 3–15ms | 1–3ms | 1–3ms |
| Infrastructure dependency | User's router | Bundled AP | None |
| Setup friction | None | Plug in AP | Pair devices |
| Multi-user support | Poor | Strong | Poor |
| Support surface | High | Low | Medium |
| BOM addition | $0 | ~$60–80 | $0 |
| Recommended for | POC only | Product | Investigate Phase 2 |

---

## Open Questions

1. What is the actual measured RTT between GMKtec and INMO Air3 on the existing 5GHz network? Measure with `ping` before assuming anything.
2. Does IMOS 3.0 on the Air3 expose WiFi Direct as a stable API? Or is it locked behind the Android stack in ways that make it unreliable?
3. Does IMOS 3.0 give low-level compositor access for timewarp, or does the OS own the display pipeline? This affects how much of the 8ms glasses-side budget is actually controllable.
4. At what rendered scene complexity does hub render time exceed 10ms on the GMKtec's Radeon 760M iGPU? Needs profiling in Phase 2.

---

## Recommended Path

**Phase 1 (now):** Do nothing. Use existing 5GHz network. Measure actual RTT once Air3 arrives.

**Phase 2 (Air3 in hand):**
- Run `ping` from Air3 to hub IP on existing WiFi. Characterize RTT mean and variance.
- Attempt WiFi Direct pairing. Test stability and RTT.
- If RTT on existing WiFi is consistently under 5ms: proceed with Option A for Phase 2 build.
- If RTT is over 8ms or highly variable: evaluate Option B (dedicated AP) before building the render pipeline.

**Phase 3+:** Revisit once translational head movement becomes the focus. At that point the network architecture is already solved and the constraint shifts to glasses-side depth fusion.

**Product decision (pre-launch):** Option B. Ship the AP. Own the stack.
