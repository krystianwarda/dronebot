// Scala
package com.dronebot.core.ports.outbound

import com.dronebot.core.domain.flight.ControlCommand


/* SimulatorOutputPort (Outbound Port) Outbound interface for sending ControlCommand instances to a flight simulator
or virtual vehicle backend. Implementations serialize and transmit commands while handling timing, acknowledgement,
and error recovery.

Responsibilities:
Convert domain ControlCommand to simulator protocol messages.
Enforce command rate and coalescing (e.g. merging small deltas).
Provide reliability (retries, backpressure, queueing) if transport is lossy.
Optionally record sent commands for replay/debugging.
Surface send failures via effect types or error channels. */

trait SimulatorOutputPort[F[_]] {
  def send(command: ControlCommand): F[Unit]
}
