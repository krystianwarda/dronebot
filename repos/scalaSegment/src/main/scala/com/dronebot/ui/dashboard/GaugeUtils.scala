package com.dronebot.ui.dashboard

object GaugeUtils {

  def quaternionToYprDegrees(
                              x0: Double, y0: Double, z0: Double, w0: Double,
                              prevOpt: Option[(Double, Double, Double, Double)] = None
                            ): ((Double, Double, Double), (Double, Double, Double, Double)) = {

    val norm = math.sqrt(x0 * x0 + y0 * y0 + z0 * z0 + w0 * w0)
    if (norm <= 1e-12) return ((0.0, 0.0, 0.0), (0.0, 0.0, 0.0, 1.0))

    var nx = x0 / norm
    var ny = y0 / norm
    var nz = z0 / norm
    var nw = w0 / norm

    val needFlip = prevOpt match {
      case Some((px0, py0, pz0, pw0)) =>
        val pnorm = math.sqrt(px0 * px0 + py0 * py0 + pz0 * pz0 + pw0 * pw0)
        val px = if (pnorm <= 1e-12) px0 else px0 / pnorm
        val py = if (pnorm <= 1e-12) py0 else py0 / pnorm
        val pz = if (pnorm <= 1e-12) pz0 else pz0 / pnorm
        val pw = if (pnorm <= 1e-12) pw0 else pw0 / pnorm
        (px * nx + py * ny + pz * nz + pw * nw) < 0.0
      case None =>
        nw < 0.0
    }

    if (needFlip) {
      nx = -nx; ny = -ny; nz = -nz; nw = -nw
    }

    // pitch (X-axis)
    val sinp = 2.0 * (nw * nx - ny * nz)
    val pitchRad =
      if (math.abs(sinp) >= 1.0) math.copySign(math.Pi / 2.0, sinp)
      else math.asin(sinp)

    // yaw (Y-axis)
    val siny_cosp = 2.0 * (nw * ny + nx * nz)
    val cosy_cosp = 1.0 - 2.0 * (ny * ny + nx * nx)
    val yawRad = math.atan2(siny_cosp, cosy_cosp)

    // roll (Z-axis)
    val sinr_cosp = 2.0 * (nw * nz + nx * ny)
    val cosr_cosp = 1.0 - 2.0 * (nz * nz + nx * nx)
    val rollRad = math.atan2(sinr_cosp, cosr_cosp)

    // base angles in deg
    val pitchDegRaw = math.toDegrees(pitchRad)
    val yawDegRaw   = math.toDegrees(yawRad)
    val rollDegRaw  = math.toDegrees(rollRad)

    // invert to match UI:
    // - yaw: turn left (west) should decrease heading on the dial ring
    // - pitch: nose down should move horizon down (positive translateY)
    // - roll: bank left should rotate horizon left
    val yawDeg   = -yawDegRaw
    val pitchDeg = -pitchDegRaw
    val rollDeg  = -rollDegRaw

    val yaw0to360 = ((yawDeg % 360.0) + 360.0) % 360.0

    ((yaw0to360, pitchDeg, rollDeg), (nx, ny, nz, nw))
  }

  private val dirs = Array(
    "N","NNE","NE","ENE","E","ESE","SE","SSE",
    "S","SSW","SW","WSW","W","WNW","NW","NNW"
  )

  def headingName16(deg: Double): String = {
    val idx = ((deg / 22.5) + 0.5).toInt % 16
    dirs(idx)
  }
}
