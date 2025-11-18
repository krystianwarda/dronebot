// Scala
package com.dronebot.adapters.simradio

import cats.effect.Sync
import com.dronebot.core.domain.flight.ControlCommand
import com.dronebot.core.ports.outbound.SimulatorOutputPort

/** Simple `SimulatorOutputPort` implementation that prints control commands to stdout.
 *
 * Useful for debugging or when running the core without any attached simulator / vJoy
 * backend. Can be swapped out for other implementations via wiring.
 */

final class ConsoleSimulatorOutput[F[_]](implicit F: Sync[F]) extends SimulatorOutputPort[F] {
  override def send(command: ControlCommand): F[Unit] =
    F.delay(
      println(f"[OUT] t=${command.throttle.value}%.2f y=${command.yaw.value}%.2f p=${command.pitch.value}%.2f r=${command.roll.value}%.2f")
    )
}
