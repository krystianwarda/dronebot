package com.dronebot.adapters.ui

import com.dronebot.adapters.infra.simdroneinfo.DroneTelemetry
import javafx.scene.chart.{XYChart => JFXXYChart}
import scalafx.geometry.Insets
import scalafx.scene.chart.{LineChart, NumberAxis}
import scalafx.scene.control.Label
import scalafx.scene.layout.VBox

final class AltitudeGraphView(title: String = "Altitude (Z) vs time") {

  private val xAxis = new NumberAxis() {
    label = "t [s]"
    autoRanging = true
    forceZeroInRange = false
  }

  private val yAxis = new NumberAxis() {
    label = "Z"
    autoRanging = true
    forceZeroInRange = false
  }

  // Use JavaFX series to avoid ScalaFX/JavaFX interop issues
  private val series = new JFXXYChart.Series[Number, Number]()
  series.setName("Altitude")

  private val chart = new LineChart[Number, Number](xAxis, yAxis) {
    animated = false
    legendVisible = false
    createSymbols = false
    prefWidth = 500
    prefHeight = 250
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
  }
  // Attach series to the chart's JavaFX data list
  chart.data().add(series)

  private var t0: Option[Double] = None
  private var lastSecSample: Option[Long] = None

  val node: VBox = new VBox(6) {
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d;"
    children = Seq(
      new Label(title) { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" },
      chart
    )
  }

  def reset(): Unit = {
    t0 = None
    lastSecSample = None
    series.getData.clear()
  }

  def update(t: DroneTelemetry): Unit = {
    if (t0.isEmpty) t0 = Some(t.timestampSec)
    val sec = math.floor(t.timestampSec).toLong
    if (lastSecSample.forall(_ != sec)) {
      val tRel = t.timestampSec - t0.getOrElse(t.timestampSec)
      series.getData.add(new JFXXYChart.Data[Number, Number](tRel, t.position.z))
      lastSecSample = Some(sec)
    }
  }
}
