// Scala
package com.dronebot.core.flightcontrol.checks

import cats.effect.kernel.Concurrent
import cats.syntax.all._
import com.dronebot.adapters.simdroneinfo.DroneTelemetry
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.flightcontrol.autopilot.Autopilot
import com.dronebot.core.flightcontrol.planner._
import fs2.Stream

/** High-level orchestration of a single test flight run. */
final class TestFlightRunner[F[_]: Concurrent](
                                                checks: FlightChecks[F],
                                                planner: FlightPlanner[F],
                                                autopilot: Autopilot[F]
                                              ) {

  /** Resolve plan, run preflight checks, then return a stream that executes the flight.
   *
   * On failure (checks or missing plan) it returns a single-element stream with no-op controller state.
   */
  def runTestFlight(
                     id: TestFlightId,
                     telemetry: Stream[F, DroneTelemetry],
                     ranges: JoystickRanges
                   ): Stream[F, ControllerState] = {

    val acquire: F[Either[PreFlightCheckResult, (FlightPlan)]] =
      for {
        planOpt <- planner.getPlan(id)
        planRes <- planOpt match {
          case None =>
            PreFlightCheckResult.TelemetryInvalidData.asLeft[FlightPlan].pure[F]
          case Some(plan) =>
            checks.runAll.map {
              case PreFlightCheckResult.Ok => plan.asRight[PreFlightCheckResult]
              case err                     => err.asLeft[FlightPlan]
            }
        }
      } yield planRes

    Stream.eval(acquire).flatMap {
      case Left(_) =>
        // Fallback: neutral no-op stream, or could be Stream.empty.
        Stream.empty

      case Right(plan) =>
        autopilot.followPlan(telemetry, ranges, plan)
    }
  }
}
