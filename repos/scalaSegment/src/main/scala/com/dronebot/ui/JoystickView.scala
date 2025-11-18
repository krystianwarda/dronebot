package com.dronebot.ui

import scalafx.Includes._
import scalafx.geometry._
import scalafx.scene.control._
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.shape.Circle

final class JoystickView(label: String, radius: Double) {
  private val base = new Circle {
    this.radius = JoystickView.this.radius
    fill = Color.web("#2d2d2d")
    stroke = Color.web("#555555")
    strokeWidth = 2
  }

  private val knob = new Circle {
    this.radius = 14
    fill = Color.web("#4caf50")
    stroke = Color.web("#2e7d32")
    strokeWidth = 2
  }

  val node: StackPane = new StackPane {
    prefWidth = radius * 2 + 10
    prefHeight = radius * 2 + 10
    children = Seq(base, knob)
  }

  private var nx: Double = 0.0
  private var ny: Double = 0.0

  var onChange: (Double, Double) => Unit = (_, _) => ()

  setNorm(0, 0)

  node.onMousePressed = (e: MouseEvent) => handle(e)
  node.onMouseDragged = (e: MouseEvent) => handle(e)
  node.onMouseReleased = (_: MouseEvent) => setNorm(0, 0)

  private def handle(e: MouseEvent): Unit = {
    if (node.disable.value) return
    val cx = node.width.value / 2.0
    val cy = node.height.value / 2.0
    val dx = e.x - cx
    val dy = e.y - cy
    val dist = math.hypot(dx, dy)
    val max = radius
    val (clampedDx, clampedDy) =
      if (dist <= max) (dx, dy)
      else {
        val s = max / dist
        (dx * s, dy * s)
      }
    knob.translateX = clampedDx
    knob.translateY = clampedDy
    nx = clampedDx / max
    ny = clampedDy / max
    onChange(nx, ny)
  }

  def setNorm(x: Double, y: Double): Unit = {
    val max = radius
    val cx = math.max(-1.0, math.min(1.0, x))
    val cy = math.max(-1.0, math.min(1.0, y))
    knob.translateX = cx * max
    knob.translateY = cy * max
    nx = cx
    ny = cy
    onChange(nx, ny)
  }
}

object JoystickViewHelpers {
  def axisLabeled(view: JoystickView, title: String, yPositiveUp: Boolean = false): VBox = {
    val (topText, bottomText) = if (yPositiveUp) ("1", "-1") else ("-1", "1")
    val topLbl = new Label(topText) { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val bottomLbl = new Label(bottomText) { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val leftLbl = new Label("-1") { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val rightLbl = new Label("1") { style = "-fx-text-fill: white; -fx-font-size: 10px;" }

    val axisPane = new BorderPane {
      top = topLbl
      bottom = bottomLbl
      left = leftLbl
      right = rightLbl
      center = view.node
      BorderPane.setAlignment(topLbl, Pos.Center)
      BorderPane.setAlignment(bottomLbl, Pos.Center)
      BorderPane.setAlignment(leftLbl, Pos.Center)
      BorderPane.setAlignment(rightLbl, Pos.Center)
    }

    new VBox(5) {
      children = Seq(
        new Label(title) { style = "-fx-text-fill: white; -fx-font-size: 12px;" },
        axisPane
      )
    }
  }

  def createJoysticksPanel(
                            leftJoystick: JoystickView,
                            rightJoystick: JoystickView,
                            panelTitle: String = "Joysticks"
                          ): VBox = {
    val leftWithAxis = axisLabeled(leftJoystick, "Left Joystick")
    val rightWithAxis = axisLabeled(rightJoystick, "Right Joystick", yPositiveUp = true)

    new VBox(8) {
      padding = Insets(10)
      style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
      children = Seq(
        new Label(panelTitle) {
          style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;"
        },
        new HBox(16) {
          alignment = Pos.Center
          children = Seq(leftWithAxis, rightWithAxis)
        }
      )
    }
  }
}
