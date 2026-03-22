# Privacy & data — POC scope

This document describes what the **Pri4L proof-of-concept** stores and sends so users and reviewers can reason about trust. It applies to the **current open-source prototype**, not a shipping product.

## What runs where

| Piece | Location | Role |
| --- | --- | --- |
| Hub stack | Your PC (Ubuntu), on the **local network** | RTAB-Map map database, ROS2, optional LLM (Ollama), rosbridge WebSocket |
| Phone app | **Your Android device** | Optional ARCore tracking, optional camera/IMU streams to the hub over the LAN |

## Data on the hub

- **Spatial map:** RTAB-Map persists a database on disk (path depends on your launch configuration; typically under your ROS workspace or home directory). It encodes **geometry** the depth camera observed over time.
- **Session logs:** `launch_hub.sh` writes timestamped logs under `~/.ros/logs/hub/` (see script help).
- **LLM (optional):** If you run `launch_spatial_query.sh`, queries and answers flow through ROS topics; Ollama runs **locally** on the hub by default.

**Identifiers:** This POC does **not** implement accounts. Nothing here is “your name” unless you put it in a query string yourself.

## Data on the phone

- **ARCore** runs **on-device** for 6DOF tracking when AR mode is enabled.
- **Camera / IMU:** Only sent to the hub when you **turn those features on** in the app.
- **Alignment:** A manual alignment step links ARCore coordinates to the hub map frame (see `decisions/009-arcore-manual-alignment.md`). Alignment is **local** to the session unless you save or log it elsewhere.

## Network boundary

- **rosbridge** uses **WebSocket** (`ws://`) to the hub — typically **unencrypted** and **without authentication** in this repo.
- **Intended use:** a **trusted LAN** (e.g. your home lab). Anyone who can reach the hub IP and port can potentially **subscribe or publish** to the same topics unless you add network isolation or TLS/auth yourself.

This is **not** “safe on coffee-shop Wi‑Fi” by default.

## Household and shared networks

On a shared home or office LAN, **other devices** might reach the hub if firewall rules allow it. Treat the hub like **lab equipment**: place it on an **isolated VLAN**, **firewall the port**, or **use VPN** if you need stronger boundaries. A future product would add **pairing** and **encrypted transport**; this POC does not.

## What we are not claiming

- No enterprise compliance (HIPAA, SOC2, etc.).
- No warranty that map data cannot be combined with other sensors to infer sensitive information — **geometry** can still reveal layout and usage patterns.

## See also

- `decisions/009-arcore-manual-alignment.md` — alignment accuracy and upgrade path (e.g. AprilTag).
- `README.md` — how to run the stack and optional spatial query.
