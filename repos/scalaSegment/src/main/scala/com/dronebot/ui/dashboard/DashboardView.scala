package com.dronebot.ui.dashboard

import com.dronebot.adapters.simdroneinfo.DroneTelemetry
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.Label
import scalafx.scene.layout.{HBox, VBox}

final class DashboardView(title: String = "Dashboard", size: Double = 300.0) {
  private val gaugeSize: Double = math.max(80.0, size - 20.0)

  private val compassGauge = new CompassGauge(gaugeSize)
  private val horizonGauge = new HorizonGauge(gaugeSize)
  private val rpmGauge     = new RpmGauge(gaugeSize)

  // Keep previous canonical quaternion to avoid q <-> -q flips
  private var prevQuat: Option[(Double, Double, Double, Double)] = None

  val node: VBox = new VBox(8) {
    padding = Insets(10)
    val rowWidth = size * 3 + 40.0
    prefWidth = rowWidth; minWidth = rowWidth
    prefHeight = size + 70.0
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label(title) {
        style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;"
      },
      new HBox(16) {
        alignment = Pos.Center
        prefWidth = rowWidth
        prefHeight = size
        children = Seq(compassGauge.node, horizonGauge.node, rpmGauge.node)
      }
    )
  }

  def updateFromTelemetry(t: DroneTelemetry): Unit = {
    val ((yawDeg, pitchDeg, rollDeg), canonicalQuat) =
      GaugeUtils.quaternionToYprDegrees(
        t.attitude.x, t.attitude.y, t.attitude.z, t.attitude.w,
        prevQuat
      )

    prevQuat = Some(canonicalQuat)

    compassGauge.setHeading(yawDeg)
    horizonGauge.setAttitude(pitchDeg, rollDeg)
    rpmGauge.setRpm(t.motorRpms.headOption.getOrElse(0.0))
  }
}
