package com.dronebot.app.runtime

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.dronebot.adapters.hybridsimradio.{AutopilotStub, HybridSimRadio, SpaceToggle, TcpJsonServerRadio}
import com.dronebot.adapters.simdroneinfo.TelemetryUdpReceiver
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.{ControlCommand, ControllerState}
import com.dronebot.ui.UILayerFx
import fs2.{Pipe, Stream}
import scalafx.application.Platform

object Programs {

  def gamepad(bus: AppBus, dm: DeviceManager, ui: UILayerFx[IO]): Stream[IO, Unit] =
    Stream.eval(dm.getOrCreate).flatMap {
      case Some(vpad) =>
        val reflectToUi: Pipe[IO, ControllerState, Unit] = _.evalMap(ui.setGimbalState)
        val toDevice: Pipe[IO, ControllerState, Unit] = _.evalMap { st =>
          val cmd = ControlCommand(st.throttle, st.yaw, st.pitch, st.roll)
          vpad.send(cmd)
        }
        bus.ctrl.subscribe(64).through(toDevice)
          .merge(bus.ctrl.subscribe(64).through(reflectToUi))
          .handleErrorWith(e => Stream.eval(IO.println(s"[CMD] error: ${e.getMessage}")))
      case None =>
        Stream.empty // silent when unavailable
    }

  def telemetry(ui: UILayerFx[IO], host: String, port: Int): Stream[IO, Unit] =
    new TelemetryUdpReceiver[IO](host, port).stream.evalMap(t => ui.setDroneTelemetry(t))

  def radio(bus: AppBus, ui: UILayerFx[IO], start: Stream[IO, Unit])(radioInput: => Stream[IO, ControllerState]): Stream[IO, Unit] =
    start.drain.merge {
      radioInput.evalMap(st => bus.ctrl.publish1(st) *> ui.setGimbalState(st))
    }

  def hybrid(
              bus: AppBus,
              dm: DeviceManager,
              dispatcher: Dispatcher[IO],
              ui: UILayerFx[IO],
              bindHost: String,
              bindPort: Int,
              autopilotHz: Int,
              ranges: JoystickRanges
            ): Stream[IO, Unit] =
    Stream.eval(dm.getOrCreate).flatMap {
      case Some(_) =>
        val realRadio = TcpJsonServerRadio.stream[IO](bindHost, bindPort)
        val autopilot = AutopilotStub.stream[IO](ranges, hz = autopilotHz)
        val hybrid    = new HybridSimRadio[IO](bus.ctrl, bus.autoToggle, realRadio, autopilot)
        Stream.eval(IO.println(s"[UI] Start Hybrid Radio (TCP JSON @ $bindHost:$bindPort + Autopilot)")) >> hybrid.stream
      case None =>
        Stream.empty
    }

  def uiMain(ui: UILayerFx[IO], dispatcher: Dispatcher[IO], bus: AppBus): Stream[IO, Unit] =
    Stream.eval(IO.blocking(Platform.startup(() => ui.show()))) >>
      Stream.eval(SpaceToggle.installGlobal[IO](dispatcher, bus.autoToggle)) >>
      Stream.never[IO]
}
