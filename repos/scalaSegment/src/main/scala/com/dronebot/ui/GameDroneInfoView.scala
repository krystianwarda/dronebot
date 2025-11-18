// Scala
package com.dronebot.ui

import com.dronebot.adapters.simdroneinfo.DroneTelemetry
import com.dronebot.ui.dashboard.DashboardView
import scalafx.geometry.Insets
import scalafx.scene.control._
import scalafx.scene.layout._



final class GameDroneInfoView(title: String = "Drone Telemetry", size: Double = 150.0) {

  private val lblPos  = new Label("Pos: -")      { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblAtt  = new Label("Quat: -")     { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblVel  = new Label("Vel: -")      { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblGyro = new Label("Gyro: -")     { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblInp  = new Label("Inputs: -")   { style = "-fx-text-fill: white; -fx-font-size: 11px;" }


  private val gimbal = new DashboardView("Gimbal", size = size)

  val node: VBox = new VBox(6) {
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label(title) { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" },
      gimbal.node,
      lblPos, lblAtt, lblVel, lblGyro, lblInp
    )
  }

  def update(t: DroneTelemetry): Unit = {
    lblPos.text = f"Pos: x=${t.position.x}%.3f y=${t.position.y}%.3f z=${t.position.z}%.3f"
    lblAtt.text = f"Quat: x=${t.attitude.x}%.3f y=${t.attitude.y}%.3f z=${t.attitude.z}%.3f w=${t.attitude.w}%.3f"
    lblVel.text = f"Vel: x=${t.velocity.x}%.3f y=${t.velocity.y}%.3f z=${t.velocity.z}%.3f"
    lblGyro.text= f"Gyro: x=${t.gyro.x}%.3f y=${t.gyro.y}%.3f z=${t.gyro.z}%.3f"
    lblInp.text = f"Inputs: roll=${t.inputs.roll}%.3f pitch=${t.inputs.pitch}%.3f yaw=${t.inputs.yaw}%.3f thr=${t.inputs.throttle}%.3f"
    gimbal.updateFromTelemetry(t)
  }
}
