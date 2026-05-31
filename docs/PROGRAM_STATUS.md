# Program status

**Last updated:** 2026-05-31

**Canonical ADRs:** Every decision lives in [`decisions/`](../decisions/). This file is an **index and status dashboard** only—it does **not** replace or relocate those documents.

---

## Program snapshot

**Thesis:** Hub-and-spoke spatial AR—compute in the room, thin clients on the face ([`README.md`](../README.md)).

**Today on `main`:** Hub (RTAB-Map + RealSense + rosbridge + anchor manager) and **Track B phone POC** (ARCore + manual alignment + bidirectional anchor creation + phone-as-mapper) are working end-to-end. **Track A** (INMO Air3) has GL rendering confirmed but is **blocked on sensor orientation mapping** — see decision 011. **Active product threads:** **011** INMO integration, **010** alignment program ([`plan-010-fiducial-product-rollout.md`](plan-010-fiducial-product-rollout.md)), and closing **004** (network).

---

## Decisions register (001–010)

| ID | Title | Status | Primary roadmap / workstream | Document |
|----|--------|--------|------------------------------|----------|
| 001 | No companion puck | decided | Phone as bridge — Phase 2 / mobile | [001-no-companion-puck.md](../decisions/001-no-companion-puck.md) |
| 002 | Hybrid render split | decided | Glasses hub vs local timewarp — Phase 2+ | [002-hybrid-render-split.md](../decisions/002-hybrid-render-split.md) |
| 003 | Phone over Pi fallback | decided | Track B phone client | [003-phone-over-pi-fallback.md](../decisions/003-phone-over-pi-fallback.md) |
| 004 | Network architecture (hub↔glasses) | **open** | Phase 2 transport + Phase 5 latency | [004-network-architecture.md](../decisions/004-network-architecture.md) |
| 005 | Glasses as OOBE room scanner | decided | Phase 3 spatial coverage | [005-glasses-as-oobe-scanner.md](../decisions/005-glasses-as-oobe-scanner.md) |
| 006 | Glasses depth sensor selection | decided (leading candidate) | Phase 3 Track B | [006-glasses-depth-sensor-selection.md](../decisions/006-glasses-depth-sensor-selection.md) |
| 007 | Glasses OS — Android fork | decided | Phase 7 / dual-mode glasses | [007-glasses-os-android-fork.md](../decisions/007-glasses-os-android-fork.md) |
| 008 | Hub GPU upgrade path | planned | Phase 6 AI / inference | [008-hub-gpu-upgrade-path.md](../decisions/008-hub-gpu-upgrade-path.md) |
| 009 | ARCore manual alignment (phone POC) | Accepted | Track B — **shipped** in app | [009-arcore-manual-alignment.md](../decisions/009-arcore-manual-alignment.md) |
| 010 | Hub-integrated fiducial alignment (product) | Accepted (product direction) | Active — [`plan-010`](plan-010-fiducial-product-rollout.md) | [010-hub-integrated-fiducial-alignment.md](../decisions/010-hub-integrated-fiducial-alignment.md) |

---

## Workstreams

Status vocabulary: **Done** | **Active** | **Planned** | **Blocked** | **N/A**.

| ID | Scope | Status | Relevant decisions |
|----|--------|--------|--------------------|
| WS-HUB | RealSense, RTAB-Map, `launch_hub.sh`, map DB | Done (POC) | — |
| WS-TRACK-B-POC | Android app, ARCore, manual align | Done | 003, 009 |
| WS-010-ALIGN | Hub face fiducial, mfg extrinsics, client observe | Active | 010, 002 |
| WS-TRACK-A | INMO Air3: GL rendering confirmed, sensor orientation blocked | Active — blocked | 002, 004, 007, 011 |
| WS-NETWORK | Wi‑Fi RTT, AP vs Direct, latency | Open until **004** closes | 004 |
| WS-OOBE-DEPTH | OOBE scan, glasses depth | Planned | 005, 006 |
| WS-AI | Ollama, `spatial_query.py`, YOLO path | Partial | 008 |
| WS-PRIVACY-DOC | LAN / data copy (`docs/privacy-poc.md`) | Baseline | 001 (bridge context) |

---

## Decision 010 — gate status

Live program: [`plan-010-fiducial-product-rollout.md`](plan-010-fiducial-product-rollout.md). **Roadmap Phases 1–8** ([`roadmap.md`](../roadmap.md)) and **010-P0–P5** (this plan’s steps) use **different IDs** by design.

| Gate | Name | Status |
|------|------|--------|
| G0 | Product + interface freeze | Not started |
| G1 | Display technology down-select | Not started |
| G2 | Extrinsics + manufacturing strategy | Not started |
| G3 | Hub software + firmware (render on demand) | Not started |
| G4 | Client software (phone → glasses) | Not started |
| G5 | Integration, RF, ship bar | Not started |

Update this table when a gate is entered or passed.

---

## Open decisions

- **004 — Network architecture** — Still **open** per ADR. Blocks a **closed** product story on hub↔client **Wi‑Fi** (dedicated AP vs existing LAN vs Wi‑Fi Direct) before large-scale Phase 2/5 hardening.

---

## Cross-reference (roadmap ↔ plans ↔ ADRs)

| Roadmap area | Plans / artifacts | ADRs |
|--------------|-------------------|------|
| Phase 1 Hub | `setup.sh`, `launch_hub.sh` | — |
| Phase 2 Client | Track B POC done; 010 product; Track A open | 002, 003, 004, 007, 009, 010 |
| Phase 3 Coverage + glasses depth | Future OOBE / depth fusion | 005, 006 |
| Phase 5 Latency + infra | Overlaps **004** | 004 |
| Phase 6 AI | `spatial_query.py`, future YOLO | 008 |
| Phase 7 Glasses HW / OS | — | 007 |
| Privacy (POC doc) | [`privacy-poc.md`](privacy-poc.md) | 001 (context) |

---

## Where to look

| Need | File |
|------|------|
| What runs today | [`README.md`](../README.md) |
| Long-range phases | [`roadmap.md`](../roadmap.md) |
| Full ADR text | [`decisions/`](../decisions/) |
| 010 execution | [`plan-010-fiducial-product-rollout.md`](plan-010-fiducial-product-rollout.md) |
