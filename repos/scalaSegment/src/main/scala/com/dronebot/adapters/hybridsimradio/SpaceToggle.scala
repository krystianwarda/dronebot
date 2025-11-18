package com.dronebot.adapters.hybridsimradio

import cats.effect.Async
import cats.effect.std.Dispatcher
import fs2.concurrent.Topic
import scalafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.input.{KeyCode => JKeyCode, KeyEvent => JKeyEvent}

object SpaceToggle {

  /** Install a handler on all current FX windows and publish a toggle event on space bar. */
  def installGlobal[F[_]: Async](dispatcher: Dispatcher[F], toggle: Topic[F, Unit]): F[Unit] =
    Async[F].delay {
      Platform.runLater { () =>
        import javafx.stage.Window

        val handler = new EventHandler[JKeyEvent] {
          override def handle(ke: JKeyEvent): Unit = {
            if (ke.getCode == JKeyCode.SPACE) {
              println("[HYBRID] Space pressed")
              dispatcher.unsafeRunAndForget(toggle.publish1(()))
              ke.consume()
            }
          }
        }

        Window.getWindows.forEach { w =>
          val sc = w.getScene
          if (sc != null) sc.addEventHandler(JKeyEvent.KEY_PRESSED, handler)
        }
      }
    }
}
