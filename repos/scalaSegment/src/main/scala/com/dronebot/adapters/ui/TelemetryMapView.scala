package com.dronebot.adapters.ui

import com.dronebot.adapters.infra.simdroneinfo.DroneTelemetry
import scalafx.geometry.Insets
import scalafx.scene.canvas.Canvas
import scalafx.scene.control.Label
import scalafx.scene.layout.VBox
import scalafx.scene.paint.Color

final class TelemetryMapView(
                              label: String = "XY Map",
                              size: Double = 250,
                              pixelsPerUnit: Double = 0.5 // 1 px per world unit; adjust if you want different scaling
                            ) {

  private val canvas = new Canvas(size, size)
  private val centerX = size / 2.0
  private val centerY = size / 2.0

  // Origin is the very first x/y we see from the stream
  private var originXY: Option[(Double, Double)] = None

  // Last whole-second bucket added, to downsample to 1 Hz
  private var lastSecSample: Option[Long] = None

  // Historical trail: relative dx,dy from origin and captured z
  private var samples: Vector[(Double, Double, Double)] = Vector.empty

  val node: VBox = new VBox(6) {
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label(label) { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" },
      canvas
    )
  }

  def reset(): Unit = {
    originXY = None
    lastSecSample = None
    samples = Vector.empty
    draw()
  }

  def update(t: DroneTelemetry): Unit = {
    if (originXY.isEmpty) originXY = Some((t.position.x, t.position.y))

    val sec = math.floor(t.timestampSec).toLong
    if (lastSecSample.forall(_ != sec)) {
      val (ox, oy) = originXY.get
      val dx = t.position.x - ox
      val dy = t.position.y - oy
      val dz = t.position.z
      samples = samples :+ (dx, dy, dz)
      lastSecSample = Some(sec)
    }

    draw(t)
  }

  private def draw(current: DroneTelemetry = null): Unit = {
    val gc = canvas.graphicsContext2D

    // Background
    gc.setFill(Color.web("#1e1e1e"))
    gc.fillRect(0, 0, size, size)

    // Border
    gc.setStroke(Color.web("#555555"))
    gc.setLineWidth(1.0)
    gc.strokeRect(0, 0, size, size)

    // Center crosshair / axes
    gc.setStroke(Color.web("#444444"))
    gc.setLineWidth(1.0)
    gc.strokeLine(0, centerY, size, centerY)
    gc.strokeLine(centerX, 0, centerX, size)

    // Trail
    if (samples.nonEmpty) {
      val xs = samples.map { case (dx, _, _) => centerX + dx * pixelsPerUnit }.toArray
      val ys = samples.map { case (_, dy, _) => centerY - dy * pixelsPerUnit }.toArray

      // Historical line
      gc.setStroke(Color.web("#00bcd4"))
      gc.setLineWidth(2.0)
      gc.strokePolyline(xs, ys, xs.length)

      // Historical points
      gc.setFill(Color.web("#00bcd4"))
      samples.indices.foreach { i =>
        val px = xs(i)
        val py = ys(i)
        gc.fillOval(px - 2, py - 2, 4, 4)
      }
    }

    // Current position marker (if we have a current sample)
    if (current != null && originXY.nonEmpty) {
      val (ox, oy) = originXY.get
      val dx = current.position.x - ox
      val dy = current.position.y - oy
      val px = centerX + dx * pixelsPerUnit
      val py = centerY - dy * pixelsPerUnit

      gc.setFill(Color.web("#ff9800"))
      gc.fillOval(px - 5, py - 5, 10, 10)
      gc.setStroke(Color.web("#f57c00"))
      gc.setLineWidth(1.5)
      gc.strokeOval(px - 6, py - 6, 12, 12)
    }
  }
}
