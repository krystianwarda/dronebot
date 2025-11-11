package com.dronebot.domain

final case class Throttle(value: Double) extends AnyVal // [0,1]
final case class Yaw(value: Double)      extends AnyVal // [-1,1]
final case class Pitch(value: Double)    extends AnyVal // [-1,1]
final case class Roll(value: Double)     extends AnyVal // [-1,1]

final case class ControllerState(
  throttle: Throttle,
  yaw: Yaw,
  pitch: Pitch,
  roll: Roll,
  timestampMs: Long
)

final case class ControlCommand(
  throttle: Throttle,
  yaw: Yaw,
  pitch: Pitch,
  roll: Roll
)

