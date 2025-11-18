package com.dronebot.adapters.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.ports.inbound.ControlInputPort
import fs2.Stream

object RadioInput {
  def startStream[F[_]: Async](ranges: JoystickRanges): F[Stream[F, ControllerState]] = {
    val backend: ControlInputPort[F] = new TcpServerRadio[F](ranges)
    Async[F].delay(println("[RADIO] Using backend: tcp-server")).as(backend.controllerStream)
  }
}
