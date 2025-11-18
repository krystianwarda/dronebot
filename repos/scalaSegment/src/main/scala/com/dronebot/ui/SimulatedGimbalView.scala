package com.dronebot.ui

import scalafx.geometry._
import scalafx.scene.control._
import scalafx.scene.layout._
import scalafx.scene.paint._
import scalafx.scene.shape._
import scalafx.scene.transform._

final class SimulatedGimbalView(label: String, size: Double = 150) {

  private val base = new Rectangle {
    width = size
    height = size
    fill = Color.web("#1e1e1e")
    stroke = Color.web("#555555")
    strokeWidth = 2
  }

  private val gimbal = new Circle {
    radius = size / 3
    fill = Color.web("#ff9800")
    stroke = Color.web("#f57c00")
    strokeWidth = 3
  }

  private val pitchIndicator = new Rectangle {
    width = size / 2
    height = 4
    fill = Color.web("#2196f3")
  }

  private val rollIndicator = new Rectangle {
    width = 4
    height = size / 2
    fill = Color.web("#4caf50")
  }

  private val statusLabel = new Label("Pitch: 0.00° Roll: 0.00°") {
    style = "-fx-text-fill: white; -fx-font-size: 10px;"
  }

  private val gimbalStack = new StackPane {
    children = Seq(base, gimbal, pitchIndicator, rollIndicator)
    prefWidth = size
    prefHeight = size
  }

  val node: VBox = new VBox(5) {
    children = Seq(new Label(label) {
      style = "-fx-text-fill: white; -fx-font-weight: bold;"
    }, gimbalStack, statusLabel)
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555555; -fx-border-width: 1px;"
  }

  def update(pitch: Double, roll: Double): Unit = {
    // Pitch: rotate the blue indicator (tilt forward/backward)
    pitchIndicator.transforms.clear()
    pitchIndicator.transforms.add(new Rotate(pitch * 45, 0, 0)) // Scale to ±45°

    // Roll: rotate the green indicator (tilt left/right)
    rollIndicator.transforms.clear()
    rollIndicator.transforms.add(new Rotate(roll * 45, 0, 0)) // Scale to ±45°

    statusLabel.text = f"Pitch: ${pitch * 45}%.2f° Roll: ${roll * 45}%.2f°"
  }
}