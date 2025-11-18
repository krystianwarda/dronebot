package com.dronebot.ui.dashboard

import scalafx.scene.Group
import scalafx.scene.control.Label
import scalafx.scene.layout.{Pane, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape._
import scalafx.scene.transform.Rotate

final class HorizonGauge(gSize: Double) {
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
