// Scala
package com.dronebot.adapters.ui

import com.dronebot.adapters.infra.simdroneinfo.DroneTelemetry
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Group
import scalafx.scene.control.Label
import scalafx.scene.layout.{HBox, Pane, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape.{Arc, ArcType, Circle, Line, Polygon, Rectangle}
import scalafx.scene.transform.Rotate

final class DualCircleGimbalView(title: String = "Gimbal", size: Double = 300.0) {

  // One common diameter for all circles; derived from per-gauge width "size"
  private val gaugeSize: Double = math.max(80.0, size - 20.0)

  private val compassGauge = new CompassGauge(gaugeSize)
  private val horizonGauge = new HorizonGauge(gaugeSize)
  private val rpmGauge     = new RpmGauge(gaugeSize)

  // Put gauges in a single row with space; widen the panel accordingly
  val node: VBox = new VBox(8) {
    padding = Insets(10)
    // 3 gauges side-by-side + margins
    val rowWidth = size * 3 + 40.0
    prefWidth = rowWidth; minWidth = rowWidth
    prefHeight = size + 70.0 // gauge plus labels
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
    val (yawDeg, pitchDeg, rollDeg) = DualCircleGimbalView.quaternionToYprDegrees(
      t.attitude.x, t.attitude.y, t.attitude.z, t.attitude.w
    )
    compassGauge.setHeading(yawDeg)
    horizonGauge.setAttitude(pitchDeg, rollDeg)
    rpmGauge.setRpm(t.motorRpms.headOption.getOrElse(0.0))
  }

  // ------------- Compass (dynamic dial with fixed pointer) -------------
  private final class CompassGauge(gSize: Double) {
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
      status.text = f"Heading: ${norm}%.0f° (${DualCircleGimbalView.headingName16(norm)})"
    }
  }

  // ------------- Artificial Horizon (pitch + roll) -------------
  private final class HorizonGauge(gSize: Double) {
    private val radius = gSize / 2.0 - 6.0
    private val center = gSize / 2.0
    private val pitchScale = (radius * 0.8) / 45.0

    private val bezel = new Circle {
      centerX = center; centerY = center; radius = HorizonGauge.this.radius
      fill = Color.web("#202020")
      stroke = Color.web("#ff9800")
      strokeWidth = 2
    }

    private val clipCircle = new Circle {
      centerX = center
      centerY = center
      radius = HorizonGauge.this.radius
    }

    private val sky = new Rectangle {
      x = 0; y = -gSize; width = gSize; height = gSize + center
      fill = Color.web("#1565C0")
    }
    private val ground = new Rectangle {
      x = 0; y = center; width = gSize; height = gSize + center
      fill = Color.web("#6D4C41")
    }
    private val horizonLine = new Line {
      startX = 0; startY = center
      endX = gSize; endY = center
      stroke = Color.White; strokeWidth = 2
    }

    private val pitchMarks: Seq[Group] = (-40 to 40 by 10).filter(_ != 0).map { deg =>
      val y = center - deg * pitchScale
      val isMajor = deg % 20 == 0
      val left = new Line {
        startX = 8; endX = center - 12
        startY = y; endY = y
        stroke = Color.web("#eeeeee"); strokeWidth = if (isMajor) 2.0 else 1.2
      }
      val right = new Line {
        startX = center + 12; endX = gSize - 8
        startY = y; endY = y
        stroke = Color.web("#eeeeee"); strokeWidth = if (isMajor) 2.0 else 1.2
      }
      val lblL = new Label(f"${math.abs(deg)}°") {
        layoutX = 10; layoutY = y - 8
        style = "-fx-text-fill: white; -fx-font-size: 9px;"
      }
      val lblR = new Label(f"${math.abs(deg)}°") {
        layoutX = gSize - 28; layoutY = y - 8
        style = "-fx-text-fill: white; -fx-font-size: 9px;"
      }
      new Group { children = Seq(left, right, lblL, lblR) }
    }

    private val moving = new Group {
      children = Seq(sky, ground, horizonLine) ++ pitchMarks
    }
    private val movingRotate = new Rotate(0, center, center)
    moving.transforms += movingRotate

    private val masked = new Group {
      clip = clipCircle
      children = Seq(moving)
    }

    private val ref = new Group {
      val wings = new Line {
        startX = center - 16; endX = center + 16
        startY = center; endY = center
        stroke = Color.White; strokeWidth = 2
      }
      val body = new Line {
        startX = center; endX = center
        startY = center; endY = center + 10
        stroke = Color.White; strokeWidth = 2
      }
      val bankPointer = new Polygon {
        points ++= Seq(
          center,         center - radius + 8,
          center - 6,     center - radius + 22,
          center + 6,     center - radius + 22
        )
        fill = Color.White
      }
      children = Seq(wings, body, bankPointer)
    }

    private val status = new Label("Pitch: 0° Roll: 0°") {
      style = "-fx-text-fill: white; -fx-font-size: 11px;"
    }

    val node: VBox = new VBox(4) {
      children = Seq(
        new StackPane {
          prefWidth = gSize; prefHeight = gSize
          children = Seq(new Pane {
            prefWidth = gSize; prefHeight = gSize
            children = Seq(bezel, masked, ref)
          })
        },
        status
      )
    }

    def setAttitude(pitchDeg: Double, rollDeg: Double): Unit = {
      val pitchClamped = math.max(-45.0, math.min(45.0, pitchDeg))
      moving.translateY = pitchClamped * pitchScale
      movingRotate.angle = -rollDeg
      status.text = f"Pitch: ${pitchDeg}%.0f° Roll: ${rollDeg}%.0f°"
    }
  }

  // ------------- RPM Gauge (clockwise increasing) -------------
  private final class RpmGauge(gSize: Double) {
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
}

private object DualCircleGimbalView {
  def quaternionToYprDegrees(x: Double, y: Double, z: Double, w: Double): (Double, Double, Double) = {
    val sinr_cosp = 2.0 * (w * x + y * z)
    val cosr_cosp = 1.0 - 2.0 * (x * x + y * y)
    val roll = math.toDegrees(math.atan2(sinr_cosp, cosr_cosp))

    val sinp = 2.0 * (w * y - z * x)
    val pitch =
      if (math.abs(sinp) >= 1.0) math.toDegrees(math.copySign(math.Pi / 2.0, sinp))
      else math.toDegrees(math.asin(sinp))

    val siny_cosp = 2.0 * (w * z + x * y)
    val cosy_cosp = 1.0 - 2.0 * (y * y + z * z)
    val yaw = math.toDegrees(math.atan2(siny_cosp, cosy_cosp))
    val yaw0to360 = ((yaw % 360) + 360) % 360

    (yaw0to360, pitch, roll)
  }

  private val dirs = Array("N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW")
  def headingName16(deg: Double): String = {
    val idx = ((deg / 22.5) + 0.5).toInt % 16
    dirs(idx)
  }
}
