package com.dronebot.adapters.hybridsimradio

import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.dronebot.core.domain.flight.ControllerState
import fs2.Stream
import fs2.concurrent.Topic

final class HybridSimRadio[F[_]](
                                  ctrlTopic: Topic[F, ControllerState],
                                  toggleTopic: Topic[F, Unit],
                                  realRadioStream: Stream[F, ControllerState],
                                  autopilotStream: Stream[F, ControllerState]
                                )(implicit F: Async[F]) {

  def stream: Stream[F, Unit] =
    Stream.eval(Ref.of[F, HybridMode](HybridMode.Manual)).flatMap { modeRef =>
      Stream.eval(Ref.of[F, Option[ControllerState]](None)).flatMap { lastRealRef =>
        Stream.eval(Ref.of[F, Option[ControllerState]](None)).flatMap { lastAutoRef =>

          val toggles: Stream[F, Unit] =
            toggleTopic
              .subscribe(16)
              .evalMap(_ => handleToggle(modeRef, lastRealRef, lastAutoRef))

          val real: Stream[F, Unit] =
            realRadioStream.evalMap { st =>
              lastRealRef.set(Some(st)) *> publishIfMode(modeRef, HybridMode.Manual, st)
            }

          val auto: Stream[F, Unit] =
            autopilotStream.evalMap { st =>
              lastAutoRef.set(Some(st)) *> publishIfMode(modeRef, HybridMode.Auto, st)
            }

          toggles.merge(real).merge(auto)
        }
      }
    }

  private def publishIfMode(
                             modeRef: Ref[F, HybridMode],
                             required: HybridMode,
                             st: ControllerState
                           ): F[Unit] =
    modeRef.get.flatMap {
      case m if m == required => ctrlTopic.publish1(st).void
      case _                  => F.unit
    }

  private def handleToggle(
                            modeRef: Ref[F, HybridMode],
                            lastRealRef: Ref[F, Option[ControllerState]],
                            lastAutoRef: Ref[F, Option[ControllerState]]
                          ): F[Unit] =
    modeRef.modify(cur => {
      val next = HybridMode.toggle(cur)
      (next, next)
    }).flatMap { newMode =>
      val autopilotOn = newMode == HybridMode.Auto
      F.delay(println(s"[HYBRID] Space pressed. Autopilot -> ${if (autopilotOn) "ON" else "OFF"}")) *>
        publishHandoverState(newMode, lastRealRef, lastAutoRef)
    }

  private def publishHandoverState(
                                    mode: HybridMode,
                                    lastRealRef: Ref[F, Option[ControllerState]],
                                    lastAutoRef: Ref[F, Option[ControllerState]]
                                  ): F[Unit] =
    mode match {
      case HybridMode.Auto =>
        lastAutoRef.get.flatMap {
          case Some(st) => ctrlTopic.publish1(st).void
          case None     => F.unit
        }
      case HybridMode.Manual =>
        lastRealRef.get.flatMap {
          case Some(st) => ctrlTopic.publish1(st).void
          case None     => F.unit
        }
    }
}
