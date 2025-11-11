// scala
package com.dronebot.radiosim

import cats.effect.Async
import cats.effect.std.Dispatcher
import fs2.concurrent.Topic
import scalafx.animation.AnimationTimer

final class TestFlight[F[_]](
                              dispatcher: Dispatcher[F],
                              testTopic: Topic[F, Unit],
                              applyPositions: (Double, Double, Double, Double) => Unit,
                              onFinish: () => Unit
                            )(implicit F: Async[F]) {

  private var timerOpt: Option[AnimationTimer] = None
  private var stepStartNanos: Long = 0L
  private var stepIndex: Int = 0

  def isRunning: Boolean = timerOpt.nonEmpty

  def start(): Unit = {
    if (timerOpt.nonEmpty) return

    dispatcher.unsafeRunAndForget(testTopic.publish1(()))

    val initialPause = 5.0
    val ramp = 0.5
    val hold1 = 1.0

    stepIndex = 0
    stepStartNanos = System.nanoTime()

    def lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    def centerBoth(): Unit = applyPositions(0.0, 0.0, 0.0, 0.0)

    val timer = AnimationTimer { now =>
      val elapsed = (now - stepStartNanos) / 1e9

      stepIndex match {
        // 0: initial pause in center
        case 0 =>
          if (elapsed < initialPause) centerBoth()
          else { stepIndex = 1; stepStartNanos = System.nanoTime() }

        // 1: ramp left joystick from center (ly=0) to full down (ly=1)
        case 1 =>
          if (elapsed < ramp) {
            val t = math.max(0.0, math.min(1.0, elapsed / ramp))
            val ly = lerp(0.0, 1.0, t)
            applyPositions(0.0, ly, 0.0, 0.0)
          } else {
            stepIndex = 2; stepStartNanos = System.nanoTime()
          }

        // 2: hold full down for 1s
        case 2 =>
          if (elapsed < hold1) {
            applyPositions(0.0, 1.0, 0.0, 0.0)
          } else {
            stepIndex = 3; stepStartNanos = System.nanoTime()
          }

        // 3: ramp left from full down (1) to full up (-1) and ramp right from center (0) to -0.2 simultaneously
        case 3 =>
          if (elapsed < ramp) {
            val t = math.max(0.0, math.min(1.0, elapsed / ramp))
            val ly = lerp(1.0, -1.0, t)
            val ry = lerp(0.0, -0.2, t)
            applyPositions(0.0, ly, 0.0, ry)
          } else {
            stepIndex = 4; stepStartNanos = System.nanoTime()
          }

        // 4: hold left full up and right at -0.2 for 1s
        case 4 =>
          if (elapsed < hold1) {
            applyPositions(0.0, -1.0, 0.0, -0.2)
          } else {
            stepIndex = 5; stepStartNanos = System.nanoTime()
          }

        // 5: ramp right joystick back to center while left stays full up
        case 5 =>
          if (elapsed < ramp) {
            val t = math.max(0.0, math.min(1.0, elapsed / ramp))
            val ry = lerp(-0.2, 0.0, t)
            applyPositions(0.0, -1.0, 0.0, ry)
          } else {
            stepIndex = 6; stepStartNanos = System.nanoTime()
          }

        // 6: ramp left joystick back to center
        case 6 =>
          if (elapsed < ramp) {
            val t = math.max(0.0, math.min(1.0, elapsed / ramp))
            val ly = lerp(-1.0, 0.0, t)
            applyPositions(0.0, ly, 0.0, 0.0)
          } else {
            // finish
            timerOpt.foreach(_.stop()); timerOpt = None
            onFinish()
          }

        case _ =>
          timerOpt.foreach(_.stop()); timerOpt = None
          onFinish()
      }
    }

    timerOpt = Some(timer)
    timer.start()
  }

  def stop(): Unit = {
    timerOpt.foreach(_.stop()); timerOpt = None
    onFinish()
  }
}
