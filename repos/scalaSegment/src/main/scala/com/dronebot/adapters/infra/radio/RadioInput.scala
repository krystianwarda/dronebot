// Scala
package com.dronebot.adapters.infra.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.ControllerState

import fs2.Stream

trait RadioBackend[F[_]] {
  def name: String
  def listDevices: F[List[String]]
  def controllerStream(ranges: JoystickRanges): Stream[F, ControllerState]
}

object RadioInput {
  def startStream[F[_]: Async](ranges: JoystickRanges): F[Stream[F, ControllerState]] = {
    val F = Async[F]
    val backend: RadioBackend[F] = new TcpServerRadio[F]
    for {
      _ <- F.delay(println("[RADIO] Using backend: tcp-server"))
    } yield backend.controllerStream(ranges)
  }
}
