package com.dronebot.adapters.infra.ports

import com.dronebot.core.domain.{ControlCommand, ControllerState}
import fs2.{Stream => Fs2Stream}

trait GamepadInputPort[F[_]] {
  def controllerStream: Fs2Stream[F, ControllerState]
}

trait SimulatorOutputPort[F[_]] {
  def send(command: ControlCommand): F[Unit]
}

trait DatabasePort[F[_]] {
  def writeTelemetry(state: ControllerState): F[Unit]
}

trait UILayerPort[F[_]] {
  def setGimbalState(state: ControllerState): F[Unit]
  def onCalibrationStart: Fs2Stream[F, Unit]
  def onTestStart: Fs2Stream[F, Unit]
  def onStop: Fs2Stream[F, Unit]
}
