package com.dronebot.output

import cats.effect.Sync
import com.dronebot.domain.ControlCommand
import com.dronebot.ports.SimulatorOutputPort

/** Placeholder simulator output adapter. */
final class DummySimulatorOutput[F[_]](implicit F: Sync[F]) extends SimulatorOutputPort[F] {
  override def send(command: ControlCommand): F[Unit] = F.unit
}

/** Console logger implementation for early testing. */
final class ConsoleSimulatorOutput[F[_]](implicit F: Sync[F]) extends SimulatorOutputPort[F] {
  override def send(command: ControlCommand): F[Unit] =
    F.delay(println(f"[OUT] t=${command.throttle.value}%.2f y=${command.yaw.value}%.2f p=${command.pitch.value}%.2f r=${command.roll.value}%.2f"))
}
