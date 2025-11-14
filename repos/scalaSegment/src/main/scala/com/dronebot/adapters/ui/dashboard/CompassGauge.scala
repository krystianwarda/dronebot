package com.dronebot.adapters.ui.dashboard

import scalafx.geometry.Insets
import scalafx.scene.Group
import scalafx.scene.control.Label
import scalafx.scene.layout.{Pane, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape._
import scalafx.scene.transform.Rotate

final class CompassGauge(gSize: Double) {
  private val radius = gSize / 2.0 - 6.0
  private val center = gSize / 2.0

  private val base = new Circle {
    centerX = center; centerY = center; radius = CompassGauge.this.radius
    fill = Color.web("#202020")
    stroke = Color.web("#ff9800")
    strokeWidth = 2
  }

  private val dialRing = new Group()
  private val ringRotate = new Rotate(0, center, center)
  dialRing.transforms += ringRotate

  private def degToRad(d: Double): Double = math.toRadians(d)
  private def tickAt(angleDeg: Double, len: Double, w: Double, color: Color): Line = {
    val a = degToRad(angleDeg)
    val inner = radius - 12
    val outer = inner - len
    val x1 = center + inner * math.cos(a)
    val y1 = center + inner * math.sin(a)
    val x2 = center + outer * math.cos(a)
    val y2 = center + outer * math.sin(a)
    new Line {
      startX = x1; startY = y1; endX = x2; endY = y2
      stroke = color; strokeWidth = w
    }
  }

  private val tickNodes = (0 to 24).map { i =>
    val deg = i * 15.0
    val major = i % 3 == 0
    tickAt(
      angleDeg = degToScreen(deg),
      len = if (major) 10 else 6,
      w = if (major) 2.2 else 1.2,
      color = Color.web(if (major) "#888" else "#666")
    )
  }

  private val labels = Seq(
    ("N",   0.0), ("NE",  45.0), ("E",  90.0), ("SE", 135.0),
    ("S", 180.0), ("SW", 225.0), ("W", 270.0), ("NW", 315.0)
  ).map { case (txt, deg) =>
    val a = degToRad(degToScreen(deg))
    val r = radius - 24
    val x = center + r * math.cos(a)
    val y = center + r * math.sin(a)
    new Label(txt) {
      layoutX = x - (if (txt.length == 1) 5 else 9)
      layoutY = y - 7
      style = if (txt.length == 1)
        "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;"
      else
        "-fx-text-fill: #dddddd; -fx-font-size: 10px;"
    }
  }

  private val pointer = new Polygon {
    points ++= Seq(
      center - 6, center - radius + 8,
      center + 6, center - radius + 8,
      center,     center - radius + 22
    )
    fill = Color.web("#E91E63")
    stroke = Color.web("#AD1457")
    strokeWidth = 1.5
  }

  private val status = new Label("Heading: 000° (N)") {
    style = "-fx-text-fill: white; -fx-font-size: 11px;"
  }

  dialRing.children = tickNodes ++ labels

  val node: VBox = new VBox(4) {
    children = Seq(
      new StackPane {
        prefWidth = gSize; prefHeight = gSize
        children = Seq(new Pane {
          prefWidth = gSize; prefHeight = gSize
          children = Seq(base, dialRing, pointer)
        })
      },
      status
    )
  }

  private def degToScreen(d: Double): Double = d - 90.0

  def setHeading(headingDeg0to360: Double): Unit = {
    val norm = ((headingDeg0to360 % 360) + 360) % 360
    ringRotate.angle = norm
    status.text = f"Heading: ${norm}%.0f° (${GaugeUtils.headingName16(norm)})"
  }
}
