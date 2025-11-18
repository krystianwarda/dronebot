package com.dronebot.core.flightcontrol.checks

import cats.Monad
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._

final class FlightChecks[F[_]: Monad](
                                       telemetry: TelemetryService[F],
                                       radioHealth: RadioHealth[F]
                                     ) {

  /** High\-level API: run all pre\-flight checks. */
  def runAll: F[PreFlightCheckResult] =
    (for {
      _ <- checkTelemetryStream
      _ <- checkTelemetryFresh
      _ <- checkRadioConnected
      _ <- checkRadioResponsive
      // easy place to plug more checks in the future
    } yield PreFlightCheckResult.Ok).run

  // \-\-\- Low\-level checks \-\-\-

  private def checkTelemetryStream: Sealed[F, Unit, PreFlightCheckResult] =
    telemetry.isStreamAlive
      .ensure(identity, PreFlightCheckResult.TelemetryNotAvailable)
      .void

  private def checkTelemetryFresh: Sealed[F, Unit, PreFlightCheckResult] =
    telemetry.isFresh
      .ensure(identity, PreFlightCheckResult.TelemetryStale)
      .void

  private def checkRadioConnected: Sealed[F, Unit, PreFlightCheckResult] =
    radioHealth.isConnected
      .ensure(identity, PreFlightCheckResult.RadioNotConnected)
      .void

  private def checkRadioResponsive: Sealed[F, Unit, PreFlightCheckResult] =
    radioHealth.isResponsive
      .ensure(identity, PreFlightCheckResult.RadioNotResponsive)
      .void
}
