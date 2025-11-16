package com.dronebot.app

import cats.effect.std.Dispatcher
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import com.dronebot.adapters.infra.radio.{RadioInput, TcpServerRadio}
import com.dronebot.adapters.infra.simdroneinfo.TelemetryUdpReceiver
import com.dronebot.adapters.infra.simradio.{ConsoleSimulatorOutput, VirtualGamepadPort}
import com.dronebot.adapters.ui.UILayerFx
import com.dronebot.app.config.AppConfig
import com.dronebot.core.domain.{ControlCommand, ControllerState}
import fs2.Stream
import fs2.concurrent.Topic
import scalafx.application.Platform
import com.dronebot.adapters.infra.hybridsimradio._
import com.dronebot.adapters.infra.hybridsimradio.TcpJsonServerRadio
import com.dronebot.adapters.infra.hybridsimradio.SpaceToggle

object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    Dispatcher.sequential[IO].use { dispatcher =>
      for {
        cfg            <- AppConfig.load[IO]
        ctrlTopic      <- Topic[IO, ControllerState]
        calibrateTop   <- Topic[IO, Unit]
        testTop        <- Topic[IO, Unit]
        stopTop        <- Topic[IO, Unit]
        startRadioTop  <- Topic[IO, Unit]
        startSimTop    <- Topic[IO, Unit]
        startHybridTop <- Topic[IO, Unit]
        autoToggleTop  <- Topic[IO, Unit]
        vpadRef        <- Ref[IO].of[Option[VirtualGamepadPort[IO]]](None)

        _ <- {
          val ui = new UILayerFx[IO](
            dispatcher,
            ctrlTopic,
            calibrateTop,
            testTop,
            stopTop,
            startRadioTop,
            startSimTop,
            startHybridTop,
            autoToggleTop,
            cfg.joystick.throttle,
            cfg.joystick.yaw,
            cfg.joystick.pitch,
            cfg.joystick.roll,
            cfg.ui.width,
            cfg.ui.height,
            cfg.ui.theme
          )

          val out = new ConsoleSimulatorOutput[IO]

          val telemReceiver = new TelemetryUdpReceiver[IO]("0.0.0.0", 9001)
          val telemStream: Stream[IO, Unit] =
            telemReceiver.stream.evalMap(t => ui.setDroneTelemetry(t))

          def topicTicks(t: Topic[IO, Unit], maxQueued: Int = 1): Stream[IO, Unit] =
            t.subscribe(maxQueued).map(_ => ())

          def startVPad: IO[Option[VirtualGamepadPort[IO]]] =
            vpadRef.get.flatMap {
              case some @ Some(_) => IO.pure(some)
              case None =>
                IO.delay(VirtualGamepadPort.trySelect[IO]).flatTap(p => vpadRef.set(p))
            }

          // Reflect every controller state to:
          // - console log
          // - vJoy device
          // - UI joysticks
          val gamepadStreams: Stream[IO, Unit] =
            Stream.eval(startVPad).flatMap {
              case Some(vpad) =>
                val controlLog =
                  ctrlTopic.subscribe(64).evalMap { st =>
                    IO.println(f"[CTRL] t=${st.throttle.value}%.2f y=${st.yaw.value}%.2f p=${st.pitch.value}%.2f r=${st.roll.value}%.2f")
                  }
                val commandSend =
                  ctrlTopic.subscribe(64).evalMap { st =>
                    val cmd = ControlCommand(st.throttle, st.yaw, st.pitch, st.roll)
                    vpad.send(cmd)
                  }.handleErrorWith(e => Stream.eval(IO.println(s"[CMD] error: ${e.getMessage}")))
                val uiReflect =
                  ctrlTopic.subscribe(64).evalMap(ui.setGimbalState)

                controlLog.merge(commandSend).merge(uiReflect)

              case None =>
                Stream.eval(IO.println("[CMD] Gamepad unavailable; suppressing control movements"))
            }

          val simStarter: Stream[IO, Unit] =
            topicTicks(startSimTop).evalMap(_ =>
              startVPad.flatMap {
                case Some(_) => IO.println("[UI] Start Simulated Radio")
                case None    => IO.println("[UI] Gamepad not available; ignoring simulated radio start")
              }
            )

          val radioStarter: Stream[IO, Unit] =
            topicTicks(startRadioTop).flatMap { _ =>
              Stream.eval(RadioInput.startStream[IO](cfg.joystick)).flatMap { rstream =>
                rstream.evalMap { st =>
                  ctrlTopic.publish1(st) *> ui.setGimbalState(st)
                }
              }
            }

          // Hybrid: TCP JSON server + autopilot; Space toggles Manual/Auto
          val hybridStarter: Stream[IO, Unit] =
            topicTicks(startHybridTop).flatMap { _ =>
              Stream.eval(startVPad).flatMap {
                case Some(_) =>
                  val bindHost = "0.0.0.0"
                  val bindPort = 9000
                  Stream.eval(IO.println(s"[UI] Start Hybrid Radio (TCP JSON @ $bindHost:$bindPort + Autopilot)")) >>
                    {
                      val realRadioStream: Stream[IO, ControllerState] =
                        TcpJsonServerRadio.stream[IO](bindHost, bindPort)

                      val autopilotStream = AutopilotStub.stream[IO](cfg.joystick, hz = 50)
                      val hybrid          = new HybridSimRadio[IO](dispatcher, ctrlTopic, autoToggleTop, realRadioStream, autopilotStream)

                      hybrid.stream
                    }
                case None =>
                  Stream.eval(IO.println("[UI] Gamepad not available; ignoring hybrid start"))
              }
            }

          val calStream: Stream[IO, Unit]  = ui.onCalibrationStart.evalMap(_ => IO.println("[UI] Calibration Start"))
          val testStream: Stream[IO, Unit] = ui.onTestStart.evalMap(_ => IO.println("[UI] Test Start"))
          val stopLog: Stream[IO, Unit]    = ui.onStop.evalMap(_ => IO.println("[UI] Stop pressed"))

          val merged: Stream[IO, Unit] =
            Stream
              .emits(List(gamepadStreams, calStream, testStream, stopLog, simStarter, radioStarter, hybridStarter, telemStream))
              .covary[IO]
              .parJoinUnbounded

          IO.blocking(Platform.startup(() => ui.show())) *>
            // Install Space key toggle globally (Manual <-> Auto)
            SpaceToggle.installGlobal[IO](dispatcher, autoToggleTop) *>
            IO.race(ui.onStop.head.compile.drain, merged.compile.drain).void
        }
      } yield ()
    }
}
