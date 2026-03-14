# 008 — Hub GPU upgrade path

**Status:** planned
**Date:** 2026-03

## Decision

When Phase 6 requires real-time object detection and faster LLM inference, upgrade the hub from the current GMKtec M6 Ultra (CPU-only) to a custom mATX tower build with an NVIDIA RTX 3060 12GB.

## Current state

The GMKtec M6 Ultra (Ryzen 7640HS, 16GB DDR5, Radeon 760M iGPU) handles the current prototype workload:
- ROS2 + RTAB-Map: ~3-4GB RAM, CPU-bound, runs fine
- Ollama llama3.2:3b: ~2GB RAM, ~5s per response on CPU
- rosbridge + phone localizer: minimal overhead

The iGPU (RDNA3, 4 CUs) has negligible inference acceleration — effectively unusable for Ollama or YOLO. eGPU is not viable (no Thunderbolt/USB4 exposed on this board despite the Ryzen 7640HS supporting USB4 at silicon level).

## Why not eGPU

- GMKtec M6 Ultra does not expose Thunderbolt/USB4
- eGPU enclosures add ~$100-200 cost that doesn't carry forward
- PCIe x4 bandwidth over Thunderbolt (32 Gbps) limits GPU throughput vs native PCIe x16 (128 Gbps)
- The enclosure becomes waste when you build the tower

## GPU selection: RTX 3060 12GB

| Factor | RTX 3060 12GB | RTX 3090 24GB | RTX 4060 8GB |
|--------|--------------|--------------|-------------|
| VRAM | 12GB | 24GB | 8GB |
| Ollama 8B speed | ~2s | ~0.8s | ~1.2s |
| YOLO v8 | <10ms | <5ms | <5ms |
| TDP | 170W | 350W | 115W |
| Used price | ~$180 | ~$700 | — |
| Max model size (q4) | ~13B | ~30B+ | ~8B |

The 3060 12GB is the right pick because:
- 12GB VRAM is the sweet spot — runs 8B comfortably, fits 13B at q4 quantization
- Cheapest option that doesn't compromise on VRAM (the 4060 has only 8GB despite being newer)
- Available and cheap on the used market
- 170W TDP is manageable with a 550W PSU
- Single 8-pin power connector — no adapter hassles

The 3090 is the upgrade path if 13B proves insufficient, but 350W TDP and $700 cost make it a later decision.

## Why NVIDIA over AMD

- Ollama/llama.cpp CUDA support is mature and plug-and-play
- ROCm (AMD) works but has driver instability, not all model architectures optimized
- YOLO ecosystem (ultralytics) assumes CUDA
- The models and software remain fully open source — GPU is just an accelerator
- Doesn't conflict with open-source hardware ethos for the rest of the stack

## Tower build spec

| Component | Pick | Est. Cost |
|-----------|------|-----------|
| GPU | RTX 3060 12GB (used) | $180 |
| CPU | Ryzen 5 5600 or 7600 | $100-150 |
| Motherboard | B550/B650 mATX | $80-120 |
| RAM | 32GB DDR4/DDR5 | $60-80 |
| PSU | 550W 80+ Bronze | $50 |
| Case | Compact mATX tower | $40-60 |
| Storage | 1TB NVMe | $0-60 |
| **Total** | | **$510-700** |

## Consequences

- GMKtec M6 Ultra is repurposed as dev/test hub or extension anchor node controller
- All software (Ubuntu 22.04, ROS2, RTAB-Map, Ollama, rosbridge) migrates unchanged
- RealSense D435i plugs into the tower via USB — no hardware change
- Tower form factor aligns with the "room tower" concept from the architecture
- 32GB RAM removes the memory ceiling — RTAB-Map + Ollama 8B + YOLO + rosbridge all fit simultaneously
- Gate: build the tower when YOLO integration begins and CPU inference speed becomes the bottleneck
