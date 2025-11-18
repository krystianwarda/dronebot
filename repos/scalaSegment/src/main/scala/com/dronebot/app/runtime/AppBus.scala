package com.dronebot.app.runtime

import cats.effect.IO
import com.dronebot.core.domain.flight.ControllerState
import fs2.concurrent.Topic

final case class AppBus(
                         ctrl: Topic[IO, ControllerState],
                         calibrate: Topic[IO, Unit],
                         test: Topic[IO, Unit],
                         stop: Topic[IO, Unit],
                         startRadio: Topic[IO, Unit],
                         startSim: Topic[IO, Unit],
                         startHybrid: Topic[IO, Unit],
                         autoToggle: Topic[IO, Unit]
                       )
