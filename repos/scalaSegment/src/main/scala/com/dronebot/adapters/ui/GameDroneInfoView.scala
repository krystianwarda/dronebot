// Scala
package com.dronebot.adapters.ui

import com.dronebot.adapters.infra.simdroneinfo.DroneTelemetry
import com.dronebot.adapters.ui.dashboard.DashboardView
import scalafx.geometry.Insets
import scalafx.scene.control._
import scalafx.scene.layout._

final class GameDroneInfoView(title: String = "Drone Telemetry") {

  private val lblPkt  = new Label("Packet: -")   { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblTs   = new Label("Time: -")     { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblPos  = new Label("Pos: -")      { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblAtt  = new Label("Quat: -")     { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblVel  = new Label("Vel: -")      { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblGyro = new Label("Gyro: -")     { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblInp  = new Label("Inputs: -")   { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblBat  = new Label("Battery: -")  { style = "-fx-text-fill: white; -fx-font-size: 11px;" }
  private val lblRpm  = new Label("RPM: -")      { style = "-fx-text-fill: white; -fx-font-size: 11px;" }

  // Keep the same width; the gimbal view will auto be ~2.6x taller and all circles equal size
  private val gimbal = new DashboardView("Gimbal", size = 180.0)

  val node: VBox = new VBox(6) {
    padding = Insets(10)
    style = "-fx-background-color: #2d2d2d; -fx-border-color: #555; -fx-border-width: 1px;"
    children = Seq(
      new Label(title) { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" },
      gimbal.node,
      lblPkt, lblTs, lblPos, lblAtt, lblVel, lblGyro, lblInp, lblBat, lblRpm
    )
  }

  def update(t: DroneTelemetry): Unit = {
    lblPkt.text = s"Packet: ${t.packetSize} bytes"
    lblTs.text  = f"Time: ${t.timestampSec}%.3fs"
    lblPos.text = f"Pos: x=${t.position.x}%.3f y=${t.position.y}%.3f z=${t.position.z}%.3f"
    lblAtt.text = f"Quat: x=${t.attitude.x}%.3f y=${t.attitude.y}%.3f z=${t.attitude.z}%.3f w=${t.attitude.w}%.3f"
    lblVel.text = f"Vel: x=${t.velocity.x}%.3f y=${t.velocity.y}%.3f z=${t.velocity.z}%.3f"
    lblGyro.text= f"Gyro: x=${t.gyro.x}%.3f y=${t.gyro.y}%.3f z=${t.gyro.z}%.3f"
    lblInp.text = f"Inputs: roll=${t.inputs.roll}%.3f pitch=${t.inputs.pitch}%.3f yaw=${t.inputs.yaw}%.3f thr=${t.inputs.throttle}%.3f"
    lblBat.text = f"Battery: V=${t.battery.voltage}%.2fV I=${t.battery.current}%.2fA"

    if (t.motorRpms.nonEmpty) {
      val rpmStr = t.motorRpms.map(r => f"$r%.0f").mkString(", ")
      lblRpm.text = s"RPM: $rpmStr"
    } else lblRpm.text = "(No MotorRPM data)"

    gimbal.updateFromTelemetry(t)
  }
}
