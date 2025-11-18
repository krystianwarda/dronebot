package com.dronebot.adapters.hybridsimradio

private[hybridsimradio] object TcpRadioCommon {
  def clamp01(x: Double): Double = math.max(0.0, math.min(1.0, x))
  def clamp11(x: Double): Double = math.max(-1.0, math.min(1.0, x))
}
