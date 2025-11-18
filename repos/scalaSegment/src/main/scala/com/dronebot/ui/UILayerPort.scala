package com.dronebot.ui

import com.dronebot.core.domain.flight.ControllerState
import fs2.{Stream => Fs2Stream}

trait UILayerPort[F[_]] extends UIEventsPort[F] {
  def setGimbalState(state: ControllerState): F[Unit]
}

trait UIEventsPort[F[_]] {
  def onCalibrationStart: Fs2Stream[F, Unit]
  def onTestStart: Fs2Stream[F, Unit]
  def onStop: Fs2Stream[F, Unit]
}
