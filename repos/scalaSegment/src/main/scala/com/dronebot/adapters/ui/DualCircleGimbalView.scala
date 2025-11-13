package com.dronebot.adapters.ui

import scalafx.geometry.Insets
import scalafx.scene.control.Label
import scalafx.scene.layout.{HBox, Pane, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape.{Circle, Line}

final class DualCircleGimbalView(title: String = "Gimbal", size: Double = 120.0) {

  private val pitchGauge = new Gauge(
    caption = "Pitch",
    pointerColor = Color.web("#2196F3") // blue
  )

  private val throttleGauge = new Gauge(
    caption = "Throttle",
    pointerColor = Color.web("#4CAF50") // green
  )

  val node: VBox = new VBox(6) {
    padding = Insets(6)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label(title) { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" },
      new HBox(10) {
        children = Seq(pitchGauge.node, throttleGauge.node)
      }
    )
  }

  // pitch in [-1, 1], throttle in [0, 1]
  def update(pitch: Double, throttle: Double): Unit = {
    pitchGauge.setPitch(pitch)
    throttleGauge.setThrottle(throttle)
  }

  private final class Gauge(caption: String, pointerColor: Color) {
    private val paneSize  = size
    private val radius    = paneSize / 2.0 - 6.0
    private val center    = paneSize / 2.0

    private val base = new Circle {
      centerX = center
      centerY = center
      radius  = DualCircleGimbalView.this.size / 2.0 - 6.0
      fill    = Color.web("#2d2d2d")
      stroke  = Color.web("#ff9800") // orange ring
      strokeWidth = 2.0
    }

    private val pointer = new Line {
      startX = center
      startY = center
      endX   = center
      endY   = center
      stroke = pointerColor
      strokeWidth = 3.0
    }

    private val plate = new Pane {
      prefWidth = paneSize
      prefHeight = paneSize
      children = Seq(base, pointer)
    }

    private val lbl = new Label(caption) {
      style = "-fx-text-fill: white; -fx-font-size: 11px;"
    }

    val node: VBox = new VBox(4) {
      children = Seq(new StackPane { children = Seq(plate) }, lbl)
    }

    // Maps pitch in [-1, 1] to a vertical pointer: up for +1, down for -1
    def setPitch(p: Double): Unit = {
      val v = math.max(-1.0, math.min(1.0, p))
      val endY = center - (v * radius) // up is negative Y
      pointer.startX = center
      pointer.startY = center
      pointer.endX = center
      pointer.endY = endY
    }

    // Maps throttle in [0, 1] to a vertical pointer: 0 at center, 1 at top
    def setThrottle(t: Double): Unit = {
      val v = math.max(0.0, math.min(1.0, t))
      val endY = center - (v * radius)
      pointer.startX = center
      pointer.startY = center
      pointer.endX = center
      pointer.endY = endY
    }
  }
}
