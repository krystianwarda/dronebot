package com.dronebot.core.ports.inbound

import com.dronebot.core.domain.flight.ControllerState
import fs2.Stream

trait ControlInputPort[F[_]] {
  def controllerStream: Stream[F, ControllerState]
}

final case class ControlSource(name: String)

trait GamepadInputPort[F[_]] {
  def controllerStream: Stream[F, ControllerState]
}
