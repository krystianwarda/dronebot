package com.dronebot.radiosim

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.dronebot.config.AxisRange
import com.dronebot.domain.{ControllerState, Pitch, Roll, Throttle, Yaw}
import com.dronebot.ports.UILayerPort
import fs2.concurrent.Topic
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.{Button, Label}
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{BorderPane, HBox, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape.Circle
import scalafx.stage.Stage

final class RadioSimUI[F[_]](
                              dispatcher: Dispatcher[F],
                              ctrlTopic: Topic[F, ControllerState],
                              calibrateTopic: Topic[F, Unit],
                              testTopic: Topic[F, Unit],
                              stopTopic: Topic[F, Unit],
                              throttleR: AxisRange,
                              yawR: AxisRange,
                              pitchR: AxisRange,
                              rollR: AxisRange,
                              uiWidth: Int,
                              uiHeight: Int,
                              uiTheme: String
                            )(implicit F: Async[F]) extends UILayerPort[F] {

  private var leftJoystickOpt: Option[JoystickView]  = None
  private var rightJoystickOpt: Option[JoystickView] = None
  private var statusLabelOpt: Option[scalafx.scene.control.Label] = None

  private val calibration = new Calibration[F](
    dispatcher,
    calibrateTopic,
    (lx, ly, rx, ry) => applyPositionsAndPublish(lx, ly, rx, ry),
    () => Platform.runLater(() => { leftJoystickOpt.foreach(_.setNorm(0.0, 0.0)); rightJoystickOpt.foreach(_.setNorm(0.0, 0.0)) })
  )

  @volatile private var last: ControllerState = ControllerState(
    throttle = Throttle(throttleR.center),
    yaw = Yaw(yawR.center),
    pitch = Pitch(pitchR.center),
    roll = Roll(rollR.center),
    timestampMs = System.currentTimeMillis()
  )

  private def clampToRange(x: Double, r: AxisRange): Double = math.max(r.min, math.min(r.max, x))

  private def publishState(t: Throttle, y: Yaw, p: Pitch, r: Roll): Unit = {
    val st = ControllerState(t, y, p, r, System.currentTimeMillis())
    last = st
    statusLabelOpt.foreach { lbl => Platform.runLater(() => lbl.text.value = f"Throttle=${st.throttle.value}%.2f Yaw=${st.yaw.value}%.2f Pitch=${st.pitch.value}%.2f Roll=${st.roll.value}%.2f") }
    dispatcher.unsafeRunAndForget(ctrlTopic.publish1(st))
  }

  private def axisLabeled(view: JoystickView, title: String): VBox = {
    val topLbl    = new scalafx.scene.control.Label("-1") { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val bottomLbl = new scalafx.scene.control.Label("1")  { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val leftLbl   = new scalafx.scene.control.Label("-1") { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val rightLbl  = new scalafx.scene.control.Label("1")  { style = "-fx-text-fill: white; -fx-font-size: 10px;" }

    val axisPane = new BorderPane {
      top = topLbl;         BorderPane.setAlignment(topLbl, Pos.Center)
      bottom = bottomLbl;   BorderPane.setAlignment(bottomLbl, Pos.Center)
      left = leftLbl;       BorderPane.setAlignment(leftLbl, Pos.Center)
      right = rightLbl;     BorderPane.setAlignment(rightLbl, Pos.Center)
      center = view.node
    }

    val titleLbl = new scalafx.scene.control.Label(title) { style = "-fx-text-fill: white;" }
    new VBox(5) { children = Seq(titleLbl, axisPane) }
  }

  private def posOnSquare(s: Double): (Double, Double) = {
    val s0 = ((s % 1.0) + 1.0) % 1.0
    val seg = (s0 * 4).toInt
    val local = (s0 * 4) - seg
    seg match {
      case 0 => (1.0 - 2.0 * local, 1.0)
      case 1 => (-1.0, 1.0 - 2.0 * local)
      case 2 => (-1.0 + 2.0 * local, -1.0)
      case _ => (1.0, -1.0 + 2.0 * local)
    }
  }

  private def applyPositionsAndPublish(lx: Double, ly: Double, rx: Double, ry: Double): Unit = {
    Platform.runLater(() => { leftJoystickOpt.foreach(_.setNorm(lx, ly)); rightJoystickOpt.foreach(_.setNorm(rx, ry)) })
    val yawV = clampToRange(lx, yawR)
    val throttleNorm = (-ly + 1.0) / 2.0
    val throttleV = clampToRange(throttleNorm, throttleR)
    val rollV = clampToRange(rx, rollR)
    val pitchV = clampToRange(-ry, pitchR)
    publishState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV))
  }

  private def startCalibration(): Unit = {
    calibration.start()
  }

  def show(): Unit = {
    val leftJoystick  = new JoystickView("Left", radius = 100)
    val rightJoystick = new JoystickView("Right", radius = 100)
    val btnCalibrate  = new Button("Calibration")
    val btnStop       = new Button("Stop")
    val statusLabel   = new Label("Idle")

    leftJoystickOpt = Some(leftJoystick)
    rightJoystickOpt = Some(rightJoystick)
    statusLabelOpt = Some(statusLabel)

    leftJoystick.onChange = { (xNorm, yNorm) =>
      if (!calibration.isRunning) {
        val yawV = clampToRange(xNorm, yawR)
        val throttleNorm = (-yNorm + 1) / 2.0
        val throttleV = clampToRange(throttleNorm, throttleR)
        publishState(Throttle(throttleV), Yaw(yawV), last.pitch, last.roll)
      }
    }

    rightJoystick.onChange = { (xNorm, yNorm) =>
      if (!calibration.isRunning) {
        val rollV = clampToRange(xNorm, rollR)
        val pitchV = clampToRange(-yNorm, pitchR)
        publishState(last.throttle, last.yaw, Pitch(pitchV), Roll(rollV))
      }
    }

    btnCalibrate.onAction = _ => startCalibration()
    btnStop.onAction      = _ => {
      dispatcher.unsafeRunAndForget(stopTopic.publish1(()))
      calibration.stop()
      Platform.runLater(() => { leftJoystickOpt.foreach(_.setNorm(0.0, 0.0)); rightJoystickOpt.foreach(_.setNorm(0.0, 0.0)) })
    }

    val leftWithAxis  = axisLabeled(leftJoystick,  "Left Joystick")
    val rightWithAxis = axisLabeled(rightJoystick, "Right Joystick")

    val rootPane = new BorderPane()
    rootPane.padding = Insets(10)
    rootPane.style = "-fx-background-color: #1e1e1e;"
    rootPane.center = new HBox(20) { children = Seq(leftWithAxis, rightWithAxis) }
    rootPane.bottom = new VBox(10) { children = Seq(new HBox(10) { children = Seq(btnCalibrate, btnStop) }, statusLabel) }

    new Stage() { title = s"scalaSegment — ${uiTheme}"; width = uiWidth; height = uiHeight; scene = new Scene(rootPane) }.show()
  }

  override def setGimbalState(state: ControllerState): F[Unit] = F.delay {
    last = state
    (leftJoystickOpt, rightJoystickOpt) match {
      case (Some(left), Some(right)) =>
        val yawX = normalizeFromRange(state.yaw.value, yawR)
        val throttleYNorm = 1.0 - (state.throttle.value - throttleR.min) / (throttleR.max - throttleR.min) * 2.0
        val pitchY = normalizeFromRange(state.pitch.value, pitchR)
        val rollX  = normalizeFromRange(state.roll.value, rollR)
        Platform.runLater { () =>
          left.setNorm(yawX, throttleYNorm)
          right.setNorm(rollX, -pitchY)
          statusLabelOpt.foreach(lbl => lbl.text.value = f"Throttle=${state.throttle.value}%.2f Yaw=${state.yaw.value}%.2f Pitch=${state.pitch.value}%.2f Roll=${state.roll.value}%.2f")
        }
      case _ => ()
    }
  }

  private def normalizeFromRange(value: Double, r: AxisRange): Double = {
    val clipped = math.max(r.min, math.min(r.max, value))
    val span = r.max - r.min
    if (span == 0) 0.0 else (clipped - r.center) / (span / 2.0)
  }

  override def onCalibrationStart: fs2.Stream[F, Unit] = calibrateTopic.subscribe(16)
  override def onTestStart: fs2.Stream[F, Unit]        = testTopic.subscribe(16)
  override def onStop: fs2.Stream[F, Unit]             = stopTopic.subscribe(16)
}

private final class JoystickView(label: String, radius: Double) {

  private val base = new Circle { this.radius = JoystickView.this.radius; fill = Color.web("#2d2d2d"); stroke = Color.web("#555555"); strokeWidth = 2 }
  private val knob = new Circle  { this.radius = 14; fill = Color.web("#4caf50"); stroke = Color.web("#2e7d32"); strokeWidth = 2 }

  val node: StackPane = new StackPane { prefWidth = radius * 2 + 10; prefHeight = radius * 2 + 10; children = Seq(base, knob) }

  private var nx: Double = 0.0
  private var ny: Double = 0.0
  var onChange: (Double, Double) => Unit = (_, _) => ()

  setNorm(0, 0)

  node.onMousePressed = (e) => handle(e)
  node.onMouseDragged = (e) => handle(e)
  node.onMouseReleased = (_) => setNorm(0, 0)

  private def handle(e: MouseEvent): Unit = {
    val cx = node.width.value / 2.0; val cy = node.height.value / 2.0
    val dx = e.x - cx; val dy = e.y - cy
    val dist = math.hypot(dx, dy); val max = radius
    val (clampedDx, clampedDy) = if (dist <= max) (dx, dy) else { val s = max / dist; (dx * s, dy * s) }
    knob.translateX = clampedDx; knob.translateY = clampedDy
    nx = clampedDx / max; ny = clampedDy / max
    onChange(nx, ny)
  }

  def setNorm(x: Double, y: Double): Unit = {
    val max = radius
    val cx = math.max(-1.0, math.min(1.0, x))
    val cy = math.max(-1.0, math.min(1.0, y))
    knob.translateX = cx * max; knob.translateY = cy * max
    nx = cx; ny = cy; onChange(nx, ny)
  }
}
