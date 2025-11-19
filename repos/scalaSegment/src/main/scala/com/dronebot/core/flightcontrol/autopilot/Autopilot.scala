package com.dronebot.core.flightcontrol.autopilot

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._
import com.dronebot.adapters.simdroneinfo.DroneTelemetry
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight._
import com.dronebot.core.flightcontrol.planner._
import fs2.Stream

/** High-level autopilot behavior. */
trait Autopilot[F[_]] {

  /** Track a given flight plan using live telemetry. */
  def followPlan(
                  telemetry: Stream[F, DroneTelemetry],
                  ranges: JoystickRanges,
                  plan: FlightPlan
                ): Stream[F, ControllerState]
}

object Autopilot {

  /** PID-based autopilot that reuses HoverAutopilot for attitude/altitude control. */
  def pidHoverBased[F[_]: Async]: Autopilot[F] =
    new Autopilot[F] {

      override def followPlan(
                               telemetry: Stream[F, DroneTelemetry],
                               ranges: JoystickRanges,
                               plan: FlightPlan
                             ): Stream[F, ControllerState] = {

        // Very simple: use the first segment, default to holding the start waypoint.
        val seg: FlightSegment =
          plan.segments.headOption.getOrElse(FlightSegment.HoldPosition(plan.start))

        // Compute target Z based on the active segment.
        val baseHover: (DroneTelemetry, FlightSegment) => Double =
          (t, s) =>
            s match {
              case FlightSegment.HoldPosition(wp, _, _) =>
                wp.position.z.toDouble

              case sl @ FlightSegment.StraightLine(a, b, _, _) =>
                val dur  = sl.endTimeSec - sl.startTimeSec
                val relT =
                  if (dur <= 0.0) 0.0
                  else (t.timestampSec - sl.startTimeSec) / dur
                val alpha = math.max(0.0, math.min(1.0, relT))
                a.position.z + (b.position.z - a.position.z) * alpha

              case FlightSegment.CircleAround(_, _, centerZ, _, _) =>
                centerZ.getOrElse(t.position.z.toDouble)
            }

        val targetZFnAny: (DroneTelemetry, Any) => Double =
          (t, anySeg) => baseHover(t, anySeg.asInstanceOf[FlightSegment])

        val telemetryWithSeg: Stream[F, (DroneTelemetry, Any)] =
          telemetry.map(t => (t, seg: Any))

        HoverAutopilot.streamWithDynamicTarget[F](
          telemetryWithSeg = telemetryWithSeg,
          ranges           = ranges,
          targetZFn        = targetZFnAny
        )
      }
    }

  def noop[F[_]: Applicative]: Autopilot[F] =
    new Autopilot[F] {
      override def followPlan(
                               telemetry: Stream[F, DroneTelemetry],
                               ranges: JoystickRanges,
                               plan: FlightPlan
                             ): Stream[F, ControllerState] = {

        val neutralState = ControllerState(
          throttle    = Throttle(ranges.throttle.center),
          yaw         = Yaw(ranges.yaw.center),
          pitch       = Pitch(ranges.pitch.center),
          roll        = Roll(ranges.roll.center),
          timestampMs = System.currentTimeMillis()
        )

        Stream
          .emit(neutralState)
          .covary[F]
          .repeat
      }
    }
}
