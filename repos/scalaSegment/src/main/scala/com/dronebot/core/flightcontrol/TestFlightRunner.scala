package com.dronebot.core.flightcontrol

import cats.effect.Temporal
import cats.syntax.all._
import com.dronebot.adapters.infra.simradio.SimRadio

import scala.concurrent.duration._


/**
 * Reproduces the old test flight joystick sequence using pure time-based steps.
 * Sequence:
 *  - 5s center
 *  - LY: 0 -> +1 over 0.5s, hold 1s
 *  - LY: +1 -> -1 and RY: 0 -> -0.2 over 0.5s, hold 1s
 *  - RY: -0.2 -> 0 over 0.5s
 *  - LY: -1 -> 0 over 0.5s
 */
final class TestFlightRunner[F[_]](radio: SimRadio[F])(implicit F: Temporal[F]) {

  private def lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

  private def ramp(
                    from: (Double, Double, Double, Double),
                    to:   (Double, Double, Double, Double),
                    duration: FiniteDuration,
                    hz: Int = 60
                  ): F[Unit] = {
    val frames = (duration.toMillis * hz / 1000L).toInt.max(1)
    val dt     = (1000.0 / hz).millis

    def step(i: Int): F[Unit] =
      if (i >= frames) radio.setSticks(to._1, to._2, to._3, to._4)
      else {
        val t  = i.toDouble / frames
        val lx = lerp(from._1, to._1, t)
        val ly = lerp(from._2, to._2, t)
        val rx = lerp(from._3, to._3, t)
        val ry = lerp(from._4, to._4, t)
        radio.setSticks(lx, ly, rx, ry) >> F.sleep(dt) >> step(i + 1)
      }

    step(0)
  }

  private def hold(pos: (Double, Double, Double, Double), duration: FiniteDuration): F[Unit] =
    radio.setSticks(pos._1, pos._2, pos._3, pos._4) >> F.sleep(duration)

  def run(): F[Unit] = for {
    // 0: initial pause centered
    _ <- hold((0.0, 0.0, 0.0, 0.0), 5.seconds)

    // 1: ramp LY center -> full down
    _ <- ramp((0.0, 0.0, 0.0, 0.0), (0.0, 1.0, 0.0, 0.0), 0.5.seconds)

    // 2: hold LY full down
    _ <- hold((0.0, 1.0, 0.0, 0.0), 1.second)

    // 3: ramp LY full down -> full up and RY 0 -> -0.2
    _ <- ramp((0.0, 1.0, 0.0, 0.0), (0.0, -1.0, 0.0, -0.2), 0.5.seconds)

    // 4: hold LY full up and RY -0.2
    _ <- hold((0.0, -1.0, 0.0, -0.2), 1.second)

    // 5: ramp RY back to center while LY stays full up
    _ <- ramp((0.0, -1.0, 0.0, -0.2), (0.0, -1.0, 0.0, 0.0), 0.5.seconds)

    // 6: ramp LY back to center
    _ <- ramp((0.0, -1.0, 0.0, 0.0), (0.0, 0.0, 0.0, 0.0), 0.5.seconds)
  } yield ()
}
