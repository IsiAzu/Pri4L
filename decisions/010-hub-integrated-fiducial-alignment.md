# 010 — Hub-integrated fiducial alignment (product)

**Status:** Accepted (product direction)  
**Date:** 2026-03-21

**Relates to:** `009-arcore-manual-alignment.md` (current phone POC uses manual alignment; this decision describes the **product** successor).

---

## Context

Alignment between the **hub map frame** and **client tracking** (phone, later glasses) must be **repeatable**, **credible outside a lab**, and **consistent with a home appliance**—not only “good enough for a POC.”

Decision **009** established **ARCore on the phone** plus **manual alignment** at the depth camera pose. That path is **correct for prototyping** but is **user-awkward** (embody the sensor) and **error-prone** (position and heading slop).

**009** also named **AprilTag-style fiducial alignment** as the recommended upgrade: a **printed** tag at a **known** pose, observed by hub and phone. That remains a **valid engineering path** (especially for **bench** and **early field** tests).

This decision records the **product** intent: treat the **hub itself** as the alignment artifact and **when** that artifact is shown to the user.

---

## Decision

### 1. Product framing

Treat alignment as **core product behavior**, not a one-off POC step:

- **Success** = user-understood flows, **measurable** residual error, and **calm** default behavior in the home.
- **Engineering** hangs off a **state model** (idle / aligning / tracking OK / recovery) and **copy** that matches it.

### 2. “The hub is the tag”

The **fiducial pattern** is rendered on a **surface that is part of the hub product** (e.g. front face, dedicated alignment window, or bezel region)—not a loose sticker placed by the user in the room.

**Geometry:** The hub **does** need a precise **tag frame → hub (map / sensor) frame** transform. It does **not** rely on the user to measure that. The transform is **defined by design** and **locked by manufacturing**:

- Tag region in **display pixel space** + **display / glass** relative to **chassis** + **D435** (and other sensors) relative to **chassis** → **fixed extrinsics** (CAD tolerance stack + optional per-unit calibration).

**User benefit:** No separate fiducial to mount or bump; **one object** is both **the room’s brain** and the **spatial anchor** for client alignment.

**Engineering note:** This does **not** remove optical and mechanical constraints (viewing angle, moiré vs LCD/OLED, rigid display–camera assembly). Those become **SKU / DFM** requirements.

### 3. Intermittent display (not always-on)

The calibration pattern is **not** shown continuously.

| Trigger | Purpose |
|--------|---------|
| **First connect / first AR session** | Onboarding — user expects a short “aim at the hub” moment. |
| **Tracking lost / paused** (client) | Recovery — user expects help; pattern gives a **clear target**. |
| **Explicit user action** (“Refine alignment” / “Align”) | Control — avoids surprise; matches **mental model**. |
| **Optional: maintenance** | Periodic or heuristic **re-observation** only if **drift** is **defined** and value beats annoyance. |

**Default product stance:** **Calm when idle** — pattern appears **during alignment and recovery**, plus **on demand**, not as a permanent **beacon**.

### 4. “Drift” and re-show policy

**Drift** must be **defined** in product and software before automated re-show:

- **Phone-only VIO** drifts relative to the physical world; the **hub↔client** rigid transform does not fix intrinsic VIO drift—**re-observing** the hub fiducial **can** re-snap alignment.
- Triggers should **not** conflate **Wi‑Fi disconnect** with **spatial drift**; avoid flashing the pattern on **every** network glitch.

Until metrics exist, prefer **tracking health + user gesture** over aggressive automatic pop-up.

### 5. UX risks (explicit)

| Risk | Mitigation |
|------|------------|
| **Surprise** when pattern appears | Short, plain copy: why it’s showing and what to do (e.g. point phone at hub for a few seconds). |
| **False triggers** | Tie primarily to **tracking state** and **alignment confidence**, not generic errors. |
| **Privacy / social** (“why is the hub showing a pattern?”) | **Intermittent** default; copy that states **calibration only**, not recording. |
| **Screen vs print optics** | Size, brightness, anti-moire, viewing cone — **design** and **validation** gates. |

---

## Alternatives considered

| Option | Summary |
|--------|---------|
| **Printed AprilTag in room (009 upgrade)** | Strong for **lab** and **low integration**; **user placement** and **furniture** variance. Remains valid for **development** and **secondary** deployments. |
| **Constant on-hub pattern** | Simple for CV; **bad** for home (glare, power, “always on” unease). Rejected as default. |
| **Manual alignment only (009)** | Keeps POC cost low; **rejected** as **product** default. |

---

## Consequences

- **Industrial design + ME:** Hub face must accommodate a **readable** fiducial region with **known** pose vs internal sensors.
- **Firmware / hub software:** Ability to **render** pattern on demand; optional **brightness** and **duration** limits for comfort.
- **Client apps:** Flows for **onboarding**, **recovery**, and **on-demand** refine; **no** requirement that the user stand at the D435.
- **009** remains the **historical and POC** record; **010** is the **product** target for **how** alignment is **presented** and **where** the fiducial **lives**.

---

## Next artifacts (non-code)

1. **One-pager — hub alignment UX:** States (idle / aligning / tracking OK / recovery), **when** the hub surface shows a pattern, and **user actions** per state.
2. **Interface register update:** `tag_frame → hub_frame` stored vs measured; **client** observation pipeline; **optional** hub-side cross-check (future).

**Implementation plan (phased gates, BOM, risks):** `docs/plan-010-fiducial-product-rollout.md` (steps **010-P0–P5**, distinct from **Roadmap Phases 1–8** in `roadmap.md`).

---

## Open questions

- **Display technology** on the hub face (LCD vs e-paper vs printed **static** graphic behind glass): tradeoff among **refresh artifacts**, **cost**, **always-visible** vs **emissive-only-when-active**.
- **Minimum viewing distance / cone** for reliable detection from phone and glasses **FOV**.
- Whether **hub-side camera** also observes the same emissive tag for **closed-loop** calibration (future; not required to accept this decision).
