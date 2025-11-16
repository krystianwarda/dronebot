// Scala
package com.dronebot.core.flightcontrol

import cats.effect.Temporal
import cats.syntax.all._
import com.dronebot.adapters.infra.simdroneinfo.Vec3
import com.dronebot.adapters.infra.simradio.SimRadio
import com.dronebot.core.flightplanner.{DroneState, TestFlight}

final class Flight[F[_]](
                          radio: SimRadio[F],
                          flightChecks: FlightChecks[F]
                        )(implicit F: Temporal[F]) {

  private val testFlight        = new TestFlight
  private val defaultDroneState = DroneState(stepMeters = 1.0)
  private val testFlightRunner  = new TestFlightRunner[F](radio)

  def onTestFlightButton(start: Vec3): F[Unit] =
    for {
      checkResult <- flightChecks.runAll
      _ <- checkResult match {
        case PreFlightCheckResult.Ok =>
          val waypoints = testFlight.testFlight1(start)
          val _preview  = testFlight.subWaypoints(
            current    = start,
            target     = waypoints.headOption.getOrElse(start),
            droneState = defaultDroneState
          )
          testFlightRunner.run().void

        case otherFailure =>
          // Replace with your logging / UI reporting
          F.raiseError(new RuntimeException(s"Preflight failed: $otherFailure"))
      }
    } yield ()
}
