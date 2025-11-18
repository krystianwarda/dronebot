package com.dronebot.adapters.hybridsimradio

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.dronebot.adapters.simdroneinfo.{DroneTelemetry, TelemetryUdpReceiver}
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.flightcontrol.autopilot.HoverAutopilot
import fs2.Stream
import fs2.concurrent.Topic

object HybridAutopilotWiring {

  /** Builds `HybridSimRadio` using the hover autopilot stream (no DroneIO). */
  def build[F[_]: Async](
                          dispatcher: Dispatcher[F],
                          ctrlTopic: Topic[F, ControllerState],
                          toggleTopic: Topic[F, Unit],
                          realRadioStream: Stream[F, ControllerState],
                          ranges: JoystickRanges,
                          telemetryReceiverOpt: Option[TelemetryUdpReceiver[F]] = None,
                          targetZ: Double = 15.0
                        ): HybridSimRadio[F] = {
    val receiver: TelemetryUdpReceiver[F] =
      telemetryReceiverOpt.getOrElse(new TelemetryUdpReceiver[F]())

    val telemetryStream: Stream[F, DroneTelemetry] =
      receiver.stream

    val autopilotStream: Stream[F, ControllerState] =
      HoverAutopilot.stream[F](telemetryStream, ranges, targetZ)

    new HybridSimRadio[F](
      ctrlTopic        = ctrlTopic,
      toggleTopic      = toggleTopic,
      realRadioStream  = realRadioStream,
      autopilotStream  = autopilotStream
    )
  }
}
