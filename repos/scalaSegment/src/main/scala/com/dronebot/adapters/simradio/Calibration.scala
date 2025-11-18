package com.dronebot.adapters.simradio

import cats.effect.Async
import cats.effect.std.Dispatcher
import fs2.concurrent.Topic
import scalafx.animation.AnimationTimer


/** Time-based joystick calibration routine.
 *
 * This class owns a `scalafx.animation.AnimationTimer` and drives a sequence of
 * joystick positions through the provided `applyPositions` callback. The sequence is:
 *
 *  0. Initial center pause, then both sticks travel a square pattern in opposite directions,
 *     then return to center.
 *  1. Pause at center.
 *  2. Left stick: ramp to full up, hold, ramp back to center.
 *  3. Pause at center.
 *  4. Right stick: ramp to full up, hold, ramp back to center.
 *  5. Pause at center.
 *  6. Right stick: ramp to full right, hold, ramp back to center.
 *  7. Pause at center.
 *  8. Left stick: ramp to full right, hold, ramp back to center.
 *  9. Final pause, then `onFinish` is called and the timer stops.
 *
 * All timing is controlled by the constants at the top of `start()`.
 */


final class Calibration[F[_]](
                               dispatcher: Dispatcher[F],
                               calibrateTopic: Topic[F, Unit],
                               applyPositions: (Double, Double, Double, Double) => Unit,
                               onFinish: () => Unit
                             )(implicit F: Async[F]) {

  private var timerOpt: Option[AnimationTimer] = None
  private var stepStartNanos: Long = 0L
  private var stepIndex: Int = 0

  def isRunning: Boolean = timerOpt.nonEmpty

  def start(): Unit = {
    if (timerOpt.nonEmpty) return

    dispatcher.unsafeRunAndForget(calibrateTopic.publish1(()))

    // Timing constants
    val preStep1Pause   = 5.0   // initial center pause inside step1
    val rampToCorner    = 0.5
    val step1Duration   = 5.0   // loops around square
    val rampToCenter    = 0.5

    val smallRamp       = 0.5   // used for other ramps
    val hold1           = 1.0   // 1s holds for single-stick steps
    val pauseBetween    = 5.0   // 5s between steps

    // Derived durations for convenience
    val step1Total = preStep1Pause + rampToCorner + step1Duration + rampToCenter
    val singleUpTotal = smallRamp + hold1       // e.g. ramp up + hold (ends at extreme)
    val upAndBackTotal = smallRamp + hold1 + smallRamp
    val horizAndBackTotal = smallRamp + hold1 + smallRamp

    stepIndex = 0
    stepStartNanos = System.nanoTime()

    val timer = AnimationTimer { now =>
      val elapsed = (now - stepStartNanos) / 1e9

      def centerBoth(): Unit = applyPositions(0.0, 0.0, 0.0, 0.0)
      def lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

      stepIndex match {
        // Step 0: complex square-loop sequence (includes initial center pause and return-to-center)
        case 0 =>
          if (elapsed < preStep1Pause) {
            centerBoth()
          } else if (elapsed < preStep1Pause + rampToCorner) {
            val t0 = (elapsed - preStep1Pause) / rampToCorner
            val p  = math.max(0.0, math.min(1.0, t0))
            val x = lerp(0.0, 1.0, p)
            val y = lerp(0.0, 1.0, p)
            applyPositions(x, y, x, y)
          } else if (elapsed < preStep1Pause + rampToCorner + step1Duration) {
            val r = elapsed - (preStep1Pause + rampToCorner)
            val sWrap = ((r / step1Duration) * 5.0) % 1.0
            val (lx, ly) = posOnSquare(sWrap)
            val (rx, ry) = posOnSquare(1.0 - sWrap)
            applyPositions(lx, ly, rx, ry)
          } else if (elapsed < step1Total) {
            val t0 = (elapsed - (preStep1Pause + rampToCorner + step1Duration)) / rampToCenter
            val p  = math.max(0.0, math.min(1.0, t0))
            val x = lerp(1.0, 0.0, p)
            val y = lerp(1.0, 0.0, p)
            applyPositions(x, y, x, y)
          } else {
            // step1 finished -> advance to pause
            stepIndex = 1
            stepStartNanos = System.nanoTime()
          }

        // Step 1: pauseBetween center after step1
        case 1 =>
          if (elapsed < pauseBetween) centerBoth()
          else { stepIndex = 2; stepStartNanos = System.nanoTime() }

        // Step 2: move left joystick full up (ramp up + hold + ramp back) - ends at center
        case 2 =>
          if (elapsed < smallRamp) {
            val p = math.max(0.0, math.min(1.0, elapsed / smallRamp))
            val ly = lerp(0.0, -1.0, p)
            applyPositions(0.0, ly, 0.0, 0.0)
          } else if (elapsed < smallRamp + hold1) {
            // hold at full up
            applyPositions(0.0, -1.0, 0.0, 0.0)
          } else if (elapsed < upAndBackTotal) {
            // ramp back to center over smallRamp
            val t0 = (elapsed - (smallRamp + hold1)) / smallRamp
            val p = math.max(0.0, math.min(1.0, t0))
            val ly = lerp(-1.0, 0.0, p)
            applyPositions(0.0, ly, 0.0, 0.0)
          } else {
            stepIndex = 3; stepStartNanos = System.nanoTime()
          }

        // Step 3: pauseBetween center (but we keep left at full up during this pause? user asked "wait 5 seconds between steps" — keep center for clarity)
        case 3 =>
          // put both to center during inter-step wait
          if (elapsed < pauseBetween) centerBoth()
          else { stepIndex = 4; stepStartNanos = System.nanoTime() }

        // Step 4: move right joystick full up, wait 1s, then move back to center
        case 4 =>
          if (elapsed < smallRamp) {
            val p = math.max(0.0, math.min(1.0, elapsed / smallRamp))
            val ry = lerp(0.0, -1.0, p)
            applyPositions(0.0, 0.0, 0.0, ry)
          } else if (elapsed < smallRamp + hold1) {
            applyPositions(0.0, 0.0, 0.0, -1.0)
          } else if (elapsed < upAndBackTotal) {
            val t0 = (elapsed - (smallRamp + hold1)) / smallRamp
            val p = math.max(0.0, math.min(1.0, t0))
            val ry = lerp(-1.0, 0.0, p)
            applyPositions(0.0, 0.0, 0.0, ry)
          } else {
            stepIndex = 5; stepStartNanos = System.nanoTime()
          }

        // Step 5: pauseBetween center
        case 5 =>
          if (elapsed < pauseBetween) centerBoth()
          else { stepIndex = 6; stepStartNanos = System.nanoTime() }

        // Step 6: move right joystick full right, wait 1s, then back to center
        case 6 =>
          if (elapsed < smallRamp) {
            val p = math.max(0.0, math.min(1.0, elapsed / smallRamp))
            val rx = lerp(0.0, 1.0, p)
            applyPositions(0.0, 0.0, rx, 0.0)
          } else if (elapsed < smallRamp + hold1) {
            applyPositions(0.0, 0.0, 1.0, 0.0)
          } else if (elapsed < horizAndBackTotal) {
            val t0 = (elapsed - (smallRamp + hold1)) / smallRamp
            val p = math.max(0.0, math.min(1.0, t0))
            val rx = lerp(1.0, 0.0, p)
            applyPositions(0.0, 0.0, rx, 0.0)
          } else {
            stepIndex = 7; stepStartNanos = System.nanoTime()
          }

        // Step 7: pauseBetween center
        case 7 =>
          if (elapsed < pauseBetween) centerBoth()
          else { stepIndex = 8; stepStartNanos = System.nanoTime() }

        // Step 8: move left stick full right, wait 1s, then back to center
        case 8 =>
          if (elapsed < smallRamp) {
            val p = math.max(0.0, math.min(1.0, elapsed / smallRamp))
            val lx = lerp(0.0, 1.0, p)
            applyPositions(lx, 0.0, 0.0, 0.0)
          } else if (elapsed < smallRamp + hold1) {
            applyPositions(1.0, 0.0, 0.0, 0.0)
          } else if (elapsed < horizAndBackTotal) {
            val t0 = (elapsed - (smallRamp + hold1)) / smallRamp
            val p = math.max(0.0, math.min(1.0, t0))
            val lx = lerp(1.0, 0.0, p)
            applyPositions(lx, 0.0, 0.0, 0.0)
          } else {
            stepIndex = 9; stepStartNanos = System.nanoTime()
          }

        // Step 9: final pause, then finish
        case 9 =>
          if (elapsed < pauseBetween) centerBoth()
          else {
            // finish calibration
            timerOpt.foreach(_.stop()); timerOpt = None
            println("Calibration finished")
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

  private def posOnSquare(s: Double): (Double, Double) = {
    val s0 = ((s % 1.0) + 1.0) % 1.0
    val seg = (s0 * 4).toInt
    val local = (s0 * 4) - seg
    seg match {
      case 0 => (1.0 - 2.0 * local, 1.0)
      case 1 => (-1.0, 1.0 - 2.0 * local)
      case 2 => (-1.0 + 2.0 * local, -1.0)
      case _ => (1.0, -1.0 + 2.0 * local)
    }
  }
}
