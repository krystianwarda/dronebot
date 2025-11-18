package com.dronebot.core.domain.control


//Normalized stick axes: roll, pitch, yaw, throttle (consistent ranges, e.g., [-1..1] or [0..1]).
//Discrete controls: arm/disarm, flight mode selection, return‑to‑home, land, [aux switches].
//Metadata: source id (radio, sim, UI), timestamp, link quality/failsafe flag
//Purpose: a single, device‑neutral input frame that inbound/ControlInputPort exposes and adapters map to (e.g., radio, sim, UI). Avoid reusing telemetry‑specific Inputs to prevent coupling.

class ControlInput {

}
