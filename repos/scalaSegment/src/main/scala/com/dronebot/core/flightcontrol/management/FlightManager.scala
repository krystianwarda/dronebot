package com.dronebot.core.flightcontrol.management

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Ref
import cats.syntax.all._
import com.dronebot.adapters.simdroneinfo.DroneTelemetry
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.flightcontrol.autopilot.Autopilot
import com.dronebot.core.flightcontrol.checks.{FlightChecks, TestFlightRunner}
import com.dronebot.core.flightcontrol.fsm._
import com.dronebot.core.flightcontrol.planner._
import fs2.Stream

/** Facade used by `Programs` / UI to manage high-level flight lifecycle. */
final class FlightManager[F[_]: Concurrent](
                                             stateRef: Ref[F, FlightState],
                                             checks: FlightChecks[F],
                                             planner: FlightPlanner[F],
                                             autopilot: Autopilot[F]
                                           ) {

  private val runner = new TestFlightRunner[F](checks, planner, autopilot)

  def startTestFlight(
                       id: TestFlightId,
                       telemetry: Stream[F, DroneTelemetry],
                       ranges: JoystickRanges
                     ): Stream[F, ControllerState] =
    Stream.eval(stateRef.get).flatMap { current =>
      val trans = FlightStateMachine.step(current, FlightEvent.StartTestFlight(id))
      Stream.eval(stateRef.set(trans.next)) >> {
        trans.action match {
          case FlightAction.BeginTestFlight(tfId) =>
            runner.runTestFlight(tfId, telemetry, ranges)

          case FlightAction.None =>
            Stream.empty

          case FlightAction.StopAll =>
            Stream.empty
        }
      }
    }
}

object FlightManager {

  def make[F[_]: Concurrent](
                              checks: FlightChecks[F],
                              planner: FlightPlanner[F],
                              autopilot: Autopilot[F]
                            ): F[FlightManager[F]] =
    Ref.of[F, FlightState](FlightState.Idle).map { ref =>
      new FlightManager[F](ref, checks, planner, autopilot)
    }
}
