package com.dronebot.adapters.infra.hybridsimradio

sealed trait HybridMode
object HybridMode {
  case object Manual extends HybridMode
  case object Auto   extends HybridMode

  def toggle(m: HybridMode): HybridMode = m match {
    case Manual => Auto
    case Auto   => Manual
  }
}
