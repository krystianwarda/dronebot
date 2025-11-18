// Scala
package com.dronebot.ui

import scalafx.collections.ObservableBuffer
import scalafx.geometry.Insets
import scalafx.scene.control.{Button, ComboBox, Label}
import scalafx.scene.layout.{HBox, VBox}

final class ControlsPanel {
  val btnCalibrate       = new Button("Calibration")
  val btnTest            = new Button("Test Flight")
  val btnStop            = new Button("Stop")
  val btnStartRadio      = new Button("Start Radio")
  val btnStartSim        = new Button("Start Simulated Radio")
  val btnStartHybrid     = new Button("Start Hybrid Radio")
  val btnToggleAutopilot = new Button("Toggle Autopilot") // NEW

  val statusLabel = new Label("Idle") {
    style = "-fx-text-fill: white;"
  }

  val sourceSelector = new ComboBox[String](ObservableBuffer("Radio", "Simulated Radio")) {
    value = "Radio"
    promptText = "Data Source"
  }

  val node: VBox = new VBox(8) {
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label("Controls") {
        style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;"
      },
      new HBox(10) {
        children = Seq(
          new Label("Source:") { style = "-fx-text-fill: white;" },
          sourceSelector
        )
      },
      new HBox(10) {
        children = Seq(btnStartRadio, btnStartSim, btnStartHybrid, btnToggleAutopilot) // NEW
      },
      new HBox(10) {
        children = Seq(btnCalibrate, btnTest, btnStop)
      },
      statusLabel
    )
  }
}
