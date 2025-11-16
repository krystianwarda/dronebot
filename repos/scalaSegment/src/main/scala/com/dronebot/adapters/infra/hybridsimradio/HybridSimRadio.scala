// Scala
package com.dronebot.adapters.infra.hybridsimradio

import cats.effect.{Async, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.dronebot.core.domain.ControllerState
import fs2.Stream
import fs2.concurrent.Topic

final class HybridSimRadio[F[_]](
                                  dispatcher: Dispatcher[F],
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
              .evalMap { _ =>
                modeRef.modify { cur =>
                  val next = HybridMode.toggle(cur)
                  (next, next)
                }.flatMap { m =>
                  val autopilotOn = m == HybridMode.Auto
                  F.delay(println(s"[HYBRID] Space pressed. Autopilot -> ${if (autopilotOn) "ON" else "OFF"}")) *>
                    (m match {
                      case HybridMode.Auto =>
                        // Publish last autopilot as we switch into autopilot authority
                        lastAutoRef.get.flatMap {
                          case Some(st) => ctrlTopic.publish1(st).void
                          case None     => F.unit
                        }
                      case HybridMode.Manual =>
                        // Publish last real radio as we switch back to manual
                        lastRealRef.get.flatMap {
                          case Some(st) => ctrlTopic.publish1(st).void
                          case None     => F.unit
                        }
                    })
                }
              }

          val real: Stream[F, Unit] =
            realRadioStream.evalMap { st =>
              lastRealRef.set(Some(st)) *>
                modeRef.get.flatMap {
                  case HybridMode.Manual => ctrlTopic.publish1(st).void
                  case HybridMode.Auto   => F.unit
                }
            }

          val auto: Stream[F, Unit] =
            autopilotStream.evalMap { st =>
              lastAutoRef.set(Some(st)) *>
                modeRef.get.flatMap {
                  case HybridMode.Auto   => ctrlTopic.publish1(st).void
                  case HybridMode.Manual => F.unit
                }
            }

          toggles.merge(real).merge(auto)
        }
      }
    }
}
