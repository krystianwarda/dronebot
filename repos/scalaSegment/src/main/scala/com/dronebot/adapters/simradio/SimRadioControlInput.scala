package com.dronebot.adapters.simradio

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.ports.inbound.ControlInputPort
import fs2.Stream
import fs2.concurrent.Topic

final class SimRadioControlInput[F[_]] private (
                                                 ctrlTopic: Topic[F, ControllerState],
                                                 calibrateTopic: Topic[F, Unit],
                                                 testTopic: Topic[F, Unit],
                                                 stopTopic: Topic[F, Unit],
                                                 dispatcher: Dispatcher[F]
                                               )(implicit
                                                 F: Async[F],
                                                 val ranges: JoystickRanges
                                               ) extends ControlInputPort[F] {

  /** Public stream for core flight/control logic. */
  override def controllerStream: Stream[F, ControllerState] =
    ctrlTopic.subscribe(64)

  /** Called by UI when control state changes. */
  def publishControl(state: ControllerState): F[Unit] =
    ctrlTopic.publish1(state).void

  /** Hooks for UI buttons. */
  def triggerCalibrate(): F[Unit] =
    calibrateTopic.publish1(()).void

  def triggerTest(): F[Unit] =
    testTopic.publish1(()).void

  def triggerStop(): F[Unit] =
    stopTopic.publish1(()).void
}

object SimRadioControlInput {

  /** Safe constructor; allocates topics and returns the adapter. */
  def make[F[_]](
                  dispatcher: Dispatcher[F],
                  ranges: JoystickRanges
                )(implicit F: Async[F]): F[SimRadioControlInput[F]] = {
    implicit val R: JoystickRanges = ranges

    for {
      ctrlTopic      <- Topic[F, ControllerState]
      calibrateTopic <- Topic[F, Unit]
      testTopic      <- Topic[F, Unit]
      stopTopic      <- Topic[F, Unit]
    } yield new SimRadioControlInput[F](
      ctrlTopic = ctrlTopic,
      calibrateTopic = calibrateTopic,
      testTopic = testTopic,
      stopTopic = stopTopic,
      dispatcher = dispatcher
    )
  }
}
