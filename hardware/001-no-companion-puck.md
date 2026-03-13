# 001 — No companion puck

**Status:** decided  
**Date:** 2025-03

## Decision

Eliminated the companion puck (a separate carried compute device). Replaced by the phone for personal compute and connectivity.

## Reasoning

The companion puck pattern exists to offload compute that can't fit on the glasses. In a hub-and-spoke architecture, the hub absorbs all heavy compute. The puck's remaining jobs — cellular connectivity, personal context, authentication, anything that needs to leave the local network — are already handled by the phone the user carries anyway.

Adding a puck means another device to charge, carry, and pair. The phone eliminates that entirely.

## Consequences

- Phone becomes an active architectural component, not an incidental accessory
- Phase 2 phone-as-client demo directly validates this decision
- Glasses weight budget improves: no need to accommodate a puck connection
