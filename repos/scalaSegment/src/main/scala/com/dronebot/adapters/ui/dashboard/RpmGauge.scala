package com.dronebot.adapters.ui.dashboard

import scalafx.scene.control.Label
import scalafx.scene.layout.{Pane, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape._
import scalafx.scene.transform.Rotate

final class RpmGauge(gSize: Double) {
  private val radius = gSize / 2.0 - 10.0
  private val center = gSize / 2.0
  private val baseOffset = 1000.0
  private val maxRaw = 27000.0
  private val span = maxRaw - baseOffset
  private val startDeg = 150.0
  private val sweepDeg = 240.0

  private val base = new Circle {
    centerX = center; centerY = center; radius = RpmGauge.this.radius
    fill = Color.web("#202020")
    stroke = Color.web("#ff9800")
    strokeWidth = 2
  }

  private val arc = new Arc {
    centerX = center; centerY = center
    radiusX = radius - 4; radiusY = radius - 4
    startAngle = startDeg
    length = -sweepDeg
    stroke = Color.web("#444")
    strokeWidth = 8
    fill = Color.Transparent
    `type` = ArcType.Open
  }

  private val ticksPane = new Pane {
    children = (0 to 10).map { i =>
      val frac = i / 10.0
      val deg = startDeg + frac * sweepDeg
      val a = math.toRadians(deg)
      val inner = radius - 12
      val outer = radius - 4
      val x1 = center + inner * math.cos(a)
      val y1 = center + inner * math.sin(a)
      val x2 = center + outer * math.cos(a)
      val y2 = center + outer * math.sin(a)
      new Line {
        startX = x1; startY = y1; endX = x2; endY = y2
        stroke = Color.web(if (i >= 8) "#E53935" else "#777")
        strokeWidth = if (i % 2 == 0) 3 else 1.5
      }
    }
  }

  private val needle = new Line {
    startX = center; startY = center
    endX = center;   endY = center - (radius - 18)
    stroke = Color.web("#E91E63")
    strokeWidth = 3
  }
  private val needleRotate = new Rotate(startDeg, center, center)
  needle.transforms += needleRotate

  private val status = new Label("RPM: 0") {
    style = "-fx-text-fill: white; -fx-font-size: 11px;"
  }

  val node: VBox = new VBox(4) {
    children = Seq(
      new StackPane {
        prefWidth = gSize; prefHeight = gSize
        children = Seq(new Pane {
          prefWidth = gSize; prefHeight = gSize
          children = Seq(base, arc, ticksPane, needle)
        })
      },
      status
    )
  }

  def setRpm(raw: Double): Unit = {
    val adjusted = math.max(0.0, math.min(span, raw - baseOffset))
    val frac = adjusted / span
    val angle = startDeg + frac * sweepDeg
    needleRotate.angle = angle
    status.text = f"RPM: ${raw}%.0f"
  }
}
