// Scala
package com.dronebot.core.flightcontrol

import cats.effect.Temporal
import cats.syntax.all._
import com.dronebot.adapters.infra.simdroneinfo.Vec3
import com.dronebot.adapters.infra.simradio.SimRadio
import com.dronebot.core.flightplanner.{DroneState, TestFlight}

/**
 * Flight controller, driven from UI.
 *
 * UI wiring example:
 *   val radio  = new UiSimRadio[IO](applyPositions)
 *   val flight = new Flight[IO](radio)
 *   uiButton.onAction( _ => flight.onTestFlightButton(currentPos).unsafeRunAndForget() )
 */
final class Flight[F[_]](radio: SimRadio[F])(implicit F: Temporal[F]) {

  private val testFlight         = new TestFlight
  private val defaultDroneState  = DroneState(stepMeters = 1.0)
  private val testFlightRunner   = new TestFlightRunner[F](radio)

  /**
   * UI entry point for the hardcoded test flight.
   * Builds waypoints (for later use) and runs the stick sequence.
   */
  def onTestFlightButton(start: Vec3): F[Unit] = {
    val waypoints = testFlight.testFlight1(start)
    val _preview  = testFlight.subWaypoints(
      current = start,
      target = waypoints.headOption.getOrElse(start),
      droneState = defaultDroneState
    )
    testFlightRunner.run().void
  }
}
