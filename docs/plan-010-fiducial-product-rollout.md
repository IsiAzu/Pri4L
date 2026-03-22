# Plan — Hub-integrated fiducial alignment (decision 010 → product)

This document turns **decision 010** and the cross-domain VP review into a **phased engineering plan**: gates, workstreams, BOM deltas, and risks. It is a **living plan**—update as gates pass.

---

## 1. Goal and scope

**Goal:** Ship a **product-credible** alignment path where the **hub presents an on-device fiducial** on demand, the **client** (phone first, glasses later) **observes** it, and **`tag → hub` extrinsics** are **known** (design + manufacturing), not user-measured.

**In scope**

- Geometry, calibration, detection pipeline, hub **render** orchestration, client **observe** flows, **intermittent** display policy.
- **Characterization** of **viewing distance**, **FOV** (phone + narrow glasses), **emissive vs static** display tradeoffs.

**Out of scope for this plan’s first release** (unless promoted)

- TLS/auth on rosbridge (parallel security track).
- Full **anchor persistence** product layer (Phase 4 roadmap).
- Replacing **009** manual POC on day one—**bench** may keep **printed AprilTag** until hub face exists.

---

## 2. Prerequisites

| Prerequisite | Status |
| --- | --- |
| Decision 010 accepted | Done |
| Decision 009 (ARCore + manual align) working POC | Done |
| Interface register draft (`tag→hub`, client pipeline) | To write (Phase 0) |

---

## 3. Phases and gates

**Gate status (G0–G5):** maintain the **single** [gate table in `docs/PROGRAM_STATUS.md`](PROGRAM_STATUS.md). Update that file when a gate starts or passes—do **not** duplicate a second status table here.

### Phase 0 — Product + interface freeze (paper)

**Deliverables**

1. **Hub alignment UX one-pager** — states: idle / aligning / tracking OK / recovery; **when** pattern shows; user actions; copy deck.
2. **Interface register** (short): `T_tag_hub` storage format, versioning, OTA; **hub → client** signals (“show tag”, duration, timeout); **client → hub** (“tag observed”, residual error optional).
3. **Metrics definitions** — separate **VIO drift**, **alignment error**, **network** — no single conflated “bad” flag.

**Gate G0:** Review with **design + architect** — narrative matches **010**; no orphan requirements.

---

### Phase 1 — Display technology down-select (prototype)

**Objective:** Pick **V1 display path** among **emissive (LCD/OLED)**, **e-paper**, **static graphic behind glass** (no dynamic pattern), or **hybrid** (e.g. static ring + emissive center).

**Activities**

- Build **2–3** **bench** mockups (not final ID): same **tag family** (e.g. AprilTag 36h11), **variable** size and **brightness**.
- **CV characterization:** phone + (if available) **INMO** or **matching FOV** camera — **detection rate**, **jitter**, **moiré/rolling shutter** vs **PWM**, **distance** sweep, **angle** sweep.
- **Mech:** rough **stack** (glass, diffuser) effect on **contrast**.

**Gate G1:** Document **minimum angular size** of tag at **max intended viewing distance** for **phone** and **glasses FOV**; pick **V1 technology** with **known** failure modes (sunlight, night).

**Exit criteria:** “We would not ship” list is explicit; **emissive** path has **PWM/exposure** mitigation or **fallback** (longer show time, brighter, or **static** tier).

---

### Phase 2 — Extrinsics and manufacturing strategy

**Objective:** **`T_tag_hub`** is **repeatable** unit-to-unit and **stable** over **thermal** and **life**.

**Activities**

- **CAD tolerance stack:** display → glass → chassis → **D435** optical center; **budget** in mm and arcmin.
- **Process:** **active alignment** vs **shim-less** vs **golden** fixture; **per-unit** calibration sequence (charuco / multi-pose / tag self-view).
- **Thermal soak:** measure **extrinsic drift** vs **spec**; define **recal** trigger or **degrade** flag.
- **Optional:** **D435 RGB** views **rendered** tag for **closed-loop** factory check (recommended in VP review).

**Gate G2:** **Yield** model at target volume: **%** units passing **reprojection** threshold **without** manual rework; **field** recalibration policy **one paragraph**.

---

### Phase 3 — Hub software + firmware

**Objective:** Hub can **render** fiducial **on demand**, with **brightness/duration** limits; optional **verify** pass using **existing** RGB stream.

**Activities**

- **Renderer** service (compositor or dedicated process): pattern ID, **timing**, **blank** when idle.
- **Orchestration** with **client** (WebSocket or future API): **show tag** / **ack** / **timeout**.
- **Calibration blob** read/write; **OTA** hook; **version** mismatch UX.
- **Logging** (non-PII): alignment attempts, **fail** codes.

**Gate G3:** **Headless** demo: **script** triggers pattern; **hub RGB** (or external camera) confirms **detectable** tag vs **model**.

---

### Phase 4 — Client software (phone → glasses)

**Objective:** Replace **dependence on manual “stand at D435”** with **observe hub tag** flow **when** hub face is available.

**Activities**

- **Phone:** **Detect** tag in **camera** stream; **SolvePnP** or ARCore **hit-test** + tag; compute **align** transform; **state UI** per Phase 0.
- **Intermittent** policy: **onboarding**, **recovery**, **user refine** — **no** flash on **Wi‑Fi** alone.
- **Glasses (Track A):** same **math**; **FOV** and **distance** constraints from **G1**; **latency** budget for **decode**.

**Gate G4:** **End-to-end** in **lab**: **repeatable** anchor error **vs** tape measure / ground truth; **session** repeatability **N** runs.

---

### Phase 5 — Integration, RF, and ship bar

**Objective:** **Product** quality in **real home** conditions.

**Activities**

- **RF / DESENSE:** **Wi‑Fi** + **USB3** + **display** switching — **no** spurious **alignment** triggers **correlated** with **streaming** load only.
- **Soak:** **long** sessions; **VIO** drift **then** **re-observe** tag.
- **Docs:** **user-facing** alignment + **privacy** (“pattern is for calibration, not recording”).

**Gate G5:** **Ship with conditions** checklist signed — **known** limitations **documented** (e.g. hub must be **visible**, **min** light).

---

## 4. Workstreams and owners (by discipline)

| Workstream | Owns | Notes |
| --- | --- | --- |
| **Industrial design / ME** | Face aperture, **stiffness**, **thermal** path, **datum** strategy | **Blocker** if extrinsics **float** |
| **EE / HW** | Display module, **backlight/PWM**, **EMI**, **SI/PI** with **cameras** | Coordinate **PWM** with **CV** |
| **CV / perception** | Tag family, **size**, **pose** solver, **rolling shutter**, **metrics** | **Gate G1** |
| **Hub software** | Render, **calibration blob**, **OTA**, **orchestration** | **Gate G3** |
| **Client software** | Phone app **then** glasses; **UX** states | **Gate G4** |
| **QA / test** | Fixture, **repeatability**, **home** pilots | **Gate G5** |

*(On a small team, **one** person may wear **multiple** hats—gates still **apply**.)*

---

## 5. BOM deltas (product hub vs current POC tower)

| Area | Addition / change |
| --- | --- |
| **Display** | Module or **assembly** with **fiducial-capable** region; **driver** as needed |
| **Optics** | **Cover glass**, optional **diffuser** / **AR** coating |
| **Mechanical** | **Bracket** tying **display assy** to **sensor** **datum**; **sealing** if aperture |
| **Manufacturing** | **Jig**, **fixture**, optional **second** camera for **cal** (NRE) |
| **Optional SKU** | **E-paper** or **static** graphic **variant** if **emissive** fails **G1** |

**Existing:** **D435** remains; may gain **software** role in **factory** **verification**.

---

## 6. Engineering requirements (summary checklist)

- [ ] **`T_tag_hub`** spec: **format**, **accuracy**, **thermal** envelope, **OTA** versioning.
- [ ] **Minimum / maximum** **viewing distance** and **angle** for **phone** and **glasses**.
- [ ] **Brightness** and **duration** limits for **comfort** and **photosensitivity** review.
- [ ] **PWM** / **display** timing **vs** **camera** **exposure** (documented **mitigation**).
- [ ] **Drift** and **re-show** policy **implemented** as **separate** metrics from **Wi‑Fi**.
- [ ] **Hub move** semantics: **map** tied to **hub** identity (future **anchor** layer alignment).

---

## 7. Risk register (living)

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Extrinsic **drift** under **thermal** | **Blocker** | **G2** soak; **mech** stiffness; **recal** |
| **Emissive** + **CV** **unreliable** | **Major** | **G1**; **static** fallback **SKU** |
| **Glasses FOV** too **narrow** for **distance** | **Major** | **G1** **cone**; **furniture** layout **guidance** |
| User **hides** hub — **no** **LOS** | **Major** | **UX** + **placement** **guidance** |
| **Calibration** **cost** at **volume** | **Major** | **Process** **design** in **G2** |

---

## 8. Order of operations (critical path)

```text
G0 (paper) → G1 (display/CV) → G2 (extrinsics/mfg) → G3 (hub SW) → G4 (client E2E) → G5 (field + RF)
```

**Parallel** allowed: **Phase 0** with **early** **Phase 1** **mockups**; **Phase 0 UX** with **009** **printed** **tag** **bench** **tests** to **de-risk** **CV** **before** **hub** **face** **exists**.

---

## 9. References

- `decisions/010-hub-integrated-fiducial-alignment.md` — product decision.
- `decisions/009-arcore-manual-alignment.md` — current POC baseline.
- `hardware/bom.md` — update when **SKU** **solidifies**.
