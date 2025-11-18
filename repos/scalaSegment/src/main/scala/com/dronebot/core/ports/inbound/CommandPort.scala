package com.dronebot.core.ports.inbound


/* CommandPort (Inbound Port) Defines the interface for receiving high-level mission and system commands
(e.g. arm/disarm, flight mode changes, mission upload, failsafe triggers).
Adapters implementing this port (REST, CLI, scripting engine, message bus) convert external
representations into validated domain command types.

Responsibilities:
Accept raw input and map/validate to domain commands.
Expose a pull or stream API for the application core.
Enforce authorization and command rate limits where needed.
Provide idempotency or deduplication for retried external messages.
Log/trace command origin for auditing. */


class CommandPort {

}
