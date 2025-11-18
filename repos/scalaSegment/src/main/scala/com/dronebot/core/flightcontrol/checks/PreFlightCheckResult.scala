package com.dronebot.core.flightcontrol.checks

import com.dronebot.adapters.simdroneinfo.Vec3

sealed trait PreFlightCheckResult


trait TelemetryService[F[_]] {
  /** \- `true` if stream is connected and we are receiving data. */
  def isStreamAlive: F[Boolean]

  /** \- last known position; `None` if never received. */
  def lastPosition: F[Option[Vec3]]

  /** \- `true` if telemetry timestamp / sequence is fresh enough. */
  def isFresh: F[Boolean]
}

trait RadioHealth[F[_]] {
  /** \- `true` when vJoy / radio device is present and opened. */
  def isConnected: F[Boolean]

  /** \- send a quick ping (e\.g. center sticks) and check for error; `true` if OK. */
  def isResponsive: F[Boolean]
}

object PreFlightCheckResult {
  final case object Ok extends PreFlightCheckResult

  // Telemetry / simulator
  final case object TelemetryNotAvailable    extends PreFlightCheckResult
  final case object TelemetryStale           extends PreFlightCheckResult
  final case object TelemetryInvalidData     extends PreFlightCheckResult

  // Radio / vJoy
  final case object RadioNotConnected        extends PreFlightCheckResult
  final case object RadioNotResponsive       extends PreFlightCheckResult

  // Generic / future use
  final case object BatteryTooLow            extends PreFlightCheckResult
  final case object GpsNotLocked             extends PreFlightCheckResult
  final case object FailsafeNotConfigured    extends PreFlightCheckResult
}
