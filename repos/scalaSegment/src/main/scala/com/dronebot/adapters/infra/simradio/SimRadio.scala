package com.dronebot.adapters.infra.simradio


trait SimRadio[F[_]] {
  def setSticks(lx: Double, ly: Double, rx: Double, ry: Double): F[Unit]
}
