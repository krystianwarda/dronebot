package com.dronebot.core.ports.inbound


/* TelemetryPort (Inbound Port) Interface for ingesting vehicle or simulator telemetry (position, attitude,
battery, health metrics, sensor statuses). Implementations decode external protocols (e.g. MAVLink, custom UDP, serial)
into domain TelemetrySnapshot / event types.

Responsibilities:
Decode and validate message frames (CRC, schema).
Aggregate partial data into coherent snapshots.
Filter, downsample or prioritize telemetry streams.
Emit streams or subscriptions for consumers (UI, logging, analytics).
Handle connection lifecycle, reconnection and stale data detection. */


class TelemetryPort {

}
