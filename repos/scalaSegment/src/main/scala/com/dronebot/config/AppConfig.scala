package com.dronebot.config

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.generic.semiauto._

final case class AxisRange(min: Double, max: Double, center: Double)
final case class JoystickRanges(
  throttle: AxisRange, // [0,1], center 0.5
  yaw: AxisRange,      // [-1,1], center 0
  pitch: AxisRange,    // [-1,1], center 0
  roll: AxisRange      // [-1,1], center 0
)
final case class UiLayout(width: Int, height: Int, theme: String)

final case class AppConfig(
  joystick: JoystickRanges,
  ui: UiLayout
)

object AppConfig {
  // Provide readers for PureConfig
  implicit val axisRangeReader: ConfigReader[AxisRange] = deriveReader
  implicit val joystickRangesReader: ConfigReader[JoystickRanges] = deriveReader
  implicit val uiLayoutReader: ConfigReader[UiLayout] = deriveReader
  implicit val appConfigReader: ConfigReader[AppConfig] = deriveReader

  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay {
      // Explicitly load from application.conf on the classpath
      ConfigSource.resources("application.conf").loadOrThrow[AppConfig]
    }
}
