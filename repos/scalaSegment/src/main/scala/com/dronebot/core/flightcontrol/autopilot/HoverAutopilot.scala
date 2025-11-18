// Scala
package com.dronebot.core.flightcontrol.autopilot

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.adapters.simdroneinfo.{DroneTelemetry, Quaternion}
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight._
import fs2.Stream

import scala.math._

object HoverAutopilot {

  private final case class PID(kp: Double, ki: Double, kd: Double, integ: Double = 0.0, prevErr: Double = 0.0) {
    def step(err: Double, dt: Double): (Double, PID) = {
      val i2 = integ + err * dt
      val d  = if (dt > 0.0) (err - prevErr) / dt else 0.0
      val o  = kp * err + ki * i2 + kd * d
      (o, copy(integ = i2, prevErr = err))
    }
    def reset: PID = copy(integ = 0.0, prevErr = 0.0)
  }

  private def clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
  private def wrapYawErrDeg(err: Double): Double = {
    val e = (err + 180.0) % 360.0
    if (e < 0) e + 360.0 - 180.0 else e - 180.0
  }

  // Quaternion -> (pitchDeg, rollDeg, yawDeg)
  private def quatToEulerDeg(q: Quaternion): (Double, Double, Double) = {
    val (x, y, z, w) = (q.x, q.y, q.z, q.w)

    val sinr_cosp = 2.0 * (w * x + y * z)
    val cosr_cosp = 1.0 - 2.0 * (x * x + y * y)
    val roll = atan2(sinr_cosp, cosr_cosp)

    val sinp = 2.0 * (w * y - z * x)
    val pitch =
      if (abs(sinp) >= 1.0) copySign(Pi / 2.0, sinp)
      else asin(sinp)

    val siny_cosp = 2.0 * (w * z + x * y)
    val cosy_cosp = 1.0 - 2.0 * (y * y + z * z)
    val yaw = atan2(siny_cosp, cosy_cosp)

    (toDegrees(pitch), toDegrees(roll), toDegrees(yaw))
  }

  // Map normalized in [-1,1] to joystick axis using center/min/max piecewise
  private def toAxis(center: Double, minV: Double, maxV: Double, norm: Double): Double = {
    val n = clamp(norm, -1.0, 1.0)
    if (n >= 0.0) center + (maxV - center) * n
    else center + (center - minV) * n
  }

  /** Builds an autopilot stream that holds z=targetZ and initial yaw, keeping horizon level. */
  def stream[F[_]: Async](
                           telemetry: Stream[F, DroneTelemetry],
                           ranges: JoystickRanges,
                           targetZ: Double = 15.0
                         ): Stream[F, ControllerState] = {

    final case class LoopState(
                                alt: PID,
                                roll: PID,
                                pitch: PID,
                                yaw: PID,
                                lastTs: Option[Double],
                                yawTargetDeg: Option[Double]
                              )

    val init = LoopState(
      alt = PID(kp = 0.6, ki = 0.10, kd = 0.30),
      roll = PID(kp = 0.8, ki = 0.00, kd = 0.10),
      pitch = PID(kp = 0.8, ki = 0.00, kd = 0.10),
      yaw = PID(kp = 0.8, ki = 0.00, kd = 0.10),
      lastTs = None,
      yawTargetDeg = None
    )

    telemetry
      .evalMapAccumulate(init) { (st, t) =>
        val dt = st.lastTs.fold(0.02)(last => (t.timestampSec - last) match {
          case d if d.isNaN || d.isInfinite || d <= 0.0 => 0.02
          case d => d
        })

        val (pitchDeg, rollDeg, yawDeg) = quatToEulerDeg(t.attitude)
        val yawTarget = st.yawTargetDeg.orElse(Some(yawDeg))

        val altErr = targetZ - t.position.z
        val (altOut, alt1) = st.alt.step(altErr, dt)
        val throttleNorm01 = clamp(0.5 + altOut, 0.0, 1.0)
        val throttleNormSigned = (throttleNorm01 - 0.5) * 2.0

        val rollErr = -rollDeg
        val (rollOutDeg, roll1) = st.roll.step(rollErr, dt)
        val rollCmdNorm = clamp(rollOutDeg / 10.0, -1.0, 1.0)

        val pitchErr = -pitchDeg
        val (pitchOutDeg, pitch1) = st.pitch.step(pitchErr, dt)
        val pitchCmdNorm = clamp(pitchOutDeg / 10.0, -1.0, 1.0)

        val yawErr = wrapYawErrDeg(yawTarget.getOrElse(yawDeg) - yawDeg)
        val (yawOutDeg, yaw1) = st.yaw.step(yawErr, dt)
        val yawCmdNorm = clamp(yawOutDeg / 30.0, -1.0, 1.0)

        val throttleVal = toAxis(ranges.throttle.center, ranges.throttle.min, ranges.throttle.max, throttleNormSigned)
        val rollVal     = toAxis(ranges.roll.center,     ranges.roll.min,     ranges.roll.max,     rollCmdNorm)
        val pitchVal    = toAxis(ranges.pitch.center,    ranges.pitch.min,    ranges.pitch.max,    pitchCmdNorm)
        val yawVal      = toAxis(ranges.yaw.center,      ranges.yaw.min,      ranges.yaw.max,      yawCmdNorm)

        val cs = ControllerState(
          throttle    = Throttle(throttleVal),
          yaw         = Yaw(yawVal),
          pitch       = Pitch(pitchVal),
          roll        = Roll(rollVal),
          timestampMs = System.currentTimeMillis()
        )

        val st2 = st.copy(
          alt = alt1, roll = roll1, pitch = pitch1, yaw = yaw1,
          lastTs = Some(t.timestampSec),
          yawTargetDeg = yawTarget
        )

        (st2, cs).pure[F]
      }
      .map { case (_, cs) => cs }
  }
}
