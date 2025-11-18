package com.dronebot.app

import cats.effect.{IO, IOApp, Resource}
import cats.effect.std.Dispatcher
import com.dronebot.app.config.AppConfig
import com.dronebot.app.runtime.{AppBus, DeviceManager, Programs}
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.ui.UILayerFx
import fs2.Stream
import fs2.concurrent.Topic

object Main extends IOApp.Simple {

  private def mkBus: Resource[IO, AppBus] =
    Resource.eval {
      for {
        ctrl        <- Topic[IO, ControllerState]
        calibrate   <- Topic[IO, Unit]
        test        <- Topic[IO, Unit]
        stop        <- Topic[IO, Unit]
        startRadio  <- Topic[IO, Unit]
        startSim    <- Topic[IO, Unit]
        startHybrid <- Topic[IO, Unit]
        autoToggle  <- Topic[IO, Unit]
      } yield AppBus(ctrl, calibrate, test, stop, startRadio, startSim, startHybrid, autoToggle)
    }

  def run: IO[Unit] =
    (for {
      cfg        <- Resource.eval(AppConfig.load[IO])
      dispatcher <- Dispatcher.sequential[IO]
      bus        <- mkBus
      devices    <- DeviceManager.resource
      ui <- Resource.pure(
        new UILayerFx[IO](
          dispatcher,
          bus.ctrl,
          bus.calibrate,
          bus.test,
          bus.stop,
          bus.startRadio,
          bus.startSim,
          bus.startHybrid,
          bus.autoToggle,
          cfg.joystick.throttle,
          cfg.joystick.yaw,
          cfg.joystick.pitch,
          cfg.joystick.roll,
          cfg.ui.width,
          cfg.ui.height,
          cfg.ui.theme
        )
      )
    } yield (cfg, dispatcher, bus, devices, ui)).use { case (cfg, dispatcher, bus, devices, ui) =>
      val gamepad = Programs.gamepad(bus, devices, ui)
      val telem   = Programs.telemetry(ui, "0.0.0.0", 9001)
      val hybridTrigger = bus.startHybrid.subscribe(1).flatMap { _ =>
        Programs.hybrid(bus, devices, dispatcher, ui, "0.0.0.0", 9000, 50, cfg.joystick)
      }
      val uiMain  = Programs.uiMain(ui, dispatcher, bus)

      Stream.emits(List(gamepad, telem, hybridTrigger, uiMain)).parJoinUnbounded
        .interruptWhen(bus.stop.subscribe(1).as(true))
        .compile.drain
    }
}
