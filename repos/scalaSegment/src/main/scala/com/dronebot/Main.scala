package com.dronebot

import cats.effect.{IO, IOApp, Ref}
import cats.effect.std.Dispatcher
import fs2.Stream
import fs2.concurrent.Topic
import com.dronebot.config.AppConfig
import com.dronebot.domain.{ControlCommand, ControllerState}
import com.dronebot.output.ConsoleSimulatorOutput
import com.dronebot.radiosim.VirtualGamepadPort
import scalafx.application.Platform
import com.dronebot.radio.RadioInput
import com.dronebot.ui.UILayerFx

object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    Dispatcher.sequential[IO].use { dispatcher =>
      for {
        cfg           <- AppConfig.load[IO]
        ctrlTopic     <- Topic[IO, ControllerState]
        calibrateTop  <- Topic[IO, Unit]
        testTop       <- Topic[IO, Unit]
        stopTop       <- Topic[IO, Unit]
        startRadioTop <- Topic[IO, Unit]
        startSimTop   <- Topic[IO, Unit]
        vpadRef       <- Ref[IO].of[Option[VirtualGamepadPort[IO]]](None)

        _ <- {
          val ui = new UILayerFx[IO](
            dispatcher,
            ctrlTopic,
            calibrateTop,
            testTop,
            stopTop,
            startRadioTop,
            startSimTop,
            cfg.joystick.throttle,
            cfg.joystick.yaw,
            cfg.joystick.pitch,
            cfg.joystick.roll,
            cfg.ui.width,
            cfg.ui.height,
            cfg.ui.theme
          )

          val out = new ConsoleSimulatorOutput[IO]

          def topicTicks(t: Topic[IO, Unit], maxQueued: Int = 1): Stream[IO, Unit] =
            t.subscribe(maxQueued).map(_ => ())

          def startVPad: IO[Option[VirtualGamepadPort[IO]]] =
            vpadRef.get.flatMap {
              case some @ Some(_) => IO.pure(some)
              case None =>
                IO.delay(VirtualGamepadPort.trySelect[IO]).flatTap(p => vpadRef.set(p))
            }

          // Streams dependent on actual gamepad availability
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
                controlLog.merge(commandSend)
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

          val calStream: Stream[IO, Unit]  = ui.onCalibrationStart.evalMap(_ => IO.println("[UI] Calibration Start"))
          val testStream: Stream[IO, Unit] = ui.onTestStart.evalMap(_ => IO.println("[UI] Test Start"))
          val stopLog: Stream[IO, Unit]    = ui.onStop.evalMap(_ => IO.println("[UI] Stop pressed"))

          val merged: Stream[IO, Unit] =
            Stream(gamepadStreams, calStream, testStream, stopLog, simStarter, radioStarter).parJoinUnbounded

          IO.blocking(Platform.startup(() => ui.show())) *>
            IO.race(ui.onStop.head.compile.drain, merged.compile.drain).void
        }
      } yield ()
    }
}
