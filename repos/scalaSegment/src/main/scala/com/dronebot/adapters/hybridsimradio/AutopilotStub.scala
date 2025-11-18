package com.dronebot.adapters.hybridsimradio

import cats.effect.Async
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.{ControllerState, Pitch, Roll, Throttle, Yaw}
import fs2.Stream

import scala.concurrent.duration._

object AutopilotStub {

  /** Emits a neutral `ControllerState` at `hz` until canceled. */
  def stream[F[_]: Async](ranges: JoystickRanges, hz: Int = 50): Stream[F, ControllerState] = {
    val tick      = (1000.0 / hz.toDouble).millis
    val mkNeutral = () =>
      ControllerState(
        throttle    = Throttle(ranges.throttle.center),
        yaw         = Yaw(ranges.yaw.center),
        pitch       = Pitch(ranges.pitch.center),
        roll        = Roll(ranges.roll.center),
        timestampMs = System.currentTimeMillis()
      )

    Stream.awakeDelay[F](tick).map(_ => mkNeutral())
  }
}
