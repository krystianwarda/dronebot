// Scala
package com.dronebot.ui

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.dronebot.config.AxisRange
import com.dronebot.domain.{ControllerState, Pitch, Roll, Throttle, Yaw}
import com.dronebot.ports.UILayerPort
import fs2.concurrent.Topic
import fs2.{Stream => Fs2Stream}
import scalafx.Includes._
import scalafx.animation._
import scalafx.application._
import scalafx.collections.ObservableBuffer
import scalafx.geometry._
import scalafx.scene._
import scalafx.scene.control._
import scalafx.scene.input._
import scalafx.scene.layout._
import scalafx.scene.paint._
import scalafx.scene.shape._
import scalafx.stage._

final class UILayerFx[F[_]](
                             dispatcher: Dispatcher[F],
                             ctrlTopic: Topic[F, ControllerState],
                             calibrateTopic: Topic[F, Unit],
                             testTopic: Topic[F, Unit],
                             stopTopic: Topic[F, Unit],
                             startRadioTopic: Topic[F, Unit],
                             startSimTopic: Topic[F, Unit],
                             throttleR: AxisRange,
                             yawR: AxisRange,
                             pitchR: AxisRange,
                             rollR: AxisRange,
                             uiWidth: Int,
                             uiHeight: Int,
                             uiTheme: String
                           )(implicit F: Async[F]) extends UILayerPort[F] {

  private sealed trait DataSource
  private object DataSource {
    case object Radio extends DataSource
    case object Simulated extends DataSource
  }

  private var leftJoystickOpt: Option[JoystickView]  = None
  private var rightJoystickOpt: Option[JoystickView] = None
  private var statusLabelOpt: Option[Label]          = None
  private var sourceSelectorOpt: Option[ComboBox[String]] = None

  @volatile private var activeSource: DataSource = DataSource.Radio

  private var calibrationTimerOpt: Option[AnimationTimer] = None
  private var calibrationStartNanos: Long = 0L
  private var isCalibrating: Boolean = false

  @volatile private var last: ControllerState = ControllerState(
    throttle = Throttle(throttleR.center),
    yaw = Yaw(yawR.center),
    pitch = Pitch(pitchR.center),
    roll = Roll(rollR.center),
    timestampMs = System.currentTimeMillis()
  )

  private def clampToRange(x: Double, r: AxisRange): Double =
    math.max(r.min, math.min(r.max, x))

  private def publishState(t: Throttle, y: Yaw, p: Pitch, r: Roll): Unit = {
    val st = ControllerState(t, y, p, r, System.currentTimeMillis())
    last = st
    statusLabelOpt.foreach { lbl =>
      Platform.runLater(() => lbl.text = f"Throttle=${st.throttle.value}%.2f Yaw=${st.yaw.value}%.2f Pitch=${st.pitch.value}%.2f Roll=${st.roll.value}%.2f")
    }
    dispatcher.unsafeRunAndForget(ctrlTopic.publish1(st))
  }

  private def axisLabeled(view: JoystickView, title: String, yPositiveUp: Boolean = false): VBox = {
    val (topText, bottomText) = if (yPositiveUp) ("1", "-1") else ("-1", "1")
    val topLbl    = new Label(topText)  { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val bottomLbl = new Label(bottomText){ style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val leftLbl   = new Label("-1")     { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val rightLbl  = new Label("1")      { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val axisPane = new BorderPane {
      top = topLbl; bottom = bottomLbl; left = leftLbl; right = rightLbl; center = view.node
      BorderPane.setAlignment(topLbl, Pos.Center)
      BorderPane.setAlignment(bottomLbl, Pos.Center)
      BorderPane.setAlignment(leftLbl, Pos.Center)
      BorderPane.setAlignment(rightLbl, Pos.Center)
    }
    new VBox(5) { children = Seq(new Label(title) { style = "-fx-text-fill: white;" }, axisPane) }
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
    Platform.runLater(() => {
      leftJoystickOpt.foreach(_.setNorm(lx, ly))
      rightJoystickOpt.foreach(_.setNorm(rx, ry))
    })
    val yawV = clampToRange(lx, yawR)
    val throttleNorm = (-ly + 1.0) / 2.0
    val throttleV = clampToRange(throttleNorm, throttleR)
    val rollV = clampToRange(rx, rollR)
    val pitchV = clampToRange(-ry, pitchR)
    publishState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV))
  }

  private def setInputMode(ds: DataSource): Unit = {
    activeSource = ds
    val enableSimulated = ds == DataSource.Simulated
    Platform.runLater(() => {
      leftJoystickOpt.foreach(v => v.node.disable = !enableSimulated)
      rightJoystickOpt.foreach(v => v.node.disable = !enableSimulated)
      if (!enableSimulated) {
        leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
        rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
      }
    })
  }

  private def startCalibration(): Unit = {
    if (calibrationTimerOpt.nonEmpty) return
    dispatcher.unsafeRunAndForget(calibrateTopic.publish1(()))
    calibrationStartNanos = System.nanoTime()
    isCalibrating = true
    val preStep1Pause = 5.0
    val rampToCorner  = 0.5
    val step1Duration = 5.0
    val rampToCenter  = 0.5
    val postStep1Pause = 5.0
    val step2Up = 0.5
    val step2Hold = 1.0
    val step2Down = 0.5
    val total = preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause + step2Up + step2Hold + step2Down
    val loops = 5.0
    val timer = AnimationTimer { now =>
      val elapsed = (now - calibrationStartNanos) / 1e9
      def centerBoth(): Unit = applyPositionsAndPublish(0.0, 0.0, 0.0, 0.0)
      if (elapsed >= total) {
        calibrationTimerOpt.foreach(_.stop()); calibrationTimerOpt = None
        isCalibrating = false
        Platform.runLater(() => {
          leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
          rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
        })
      } else if (elapsed < preStep1Pause) {
        centerBoth()
      } else if (elapsed < preStep1Pause + rampToCorner) {
        val t0 = elapsed - preStep1Pause
        val p  = math.max(0.0, math.min(1.0, t0 / rampToCorner))
        val x = p
        val y = p
        applyPositionsAndPublish(x, y, x, y)
      } else if (elapsed < preStep1Pause + rampToCorner + step1Duration) {
        val r = elapsed - (preStep1Pause + rampToCorner)
        val s = (r / step1Duration) * loops
        val sWrap = s - math.floor(s)
        val (lx, ly) = posOnSquare(sWrap)
        val (rx, ry) = posOnSquare(1.0 - sWrap)
        applyPositionsAndPublish(lx, ly, rx, ry)
      } else if (elapsed < preStep1Pause + rampToCorner + step1Duration + rampToCenter) {
        val t0 = elapsed - (preStep1Pause + rampToCorner + step1Duration)
        val p  = math.max(0.0, math.min(1.0, t0 / rampToCenter))
        val x = 1.0 - p
        val y = 1.0 - p
        applyPositionsAndPublish(x, y, x, y)
      } else if (elapsed < preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause) {
        centerBoth()
      } else if (elapsed < preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause + step2Up) {
        val t0 = elapsed - (preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause)
        val p = math.max(0.0, math.min(1.0, t0 / step2Up))
        val ly = -1.0 * p
        applyPositionsAndPublish(0.0, ly, 0.0, 0.0)
      } else if (elapsed < preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause + step2Up + step2Hold) {
        applyPositionsAndPublish(0.0, -1.0, 0.0, 0.0)
      } else {
        val t0 = elapsed - (preStep1Pause + rampToCorner + step1Duration + rampToCenter + postStep1Pause + step2Up + step2Hold)
        val p = math.max(0.0, math.min(1.0, t0 / step2Down))
        val ly = -1.0 + p
        applyPositionsAndPublish(0.0, ly, 0.0, 0.0)
      }
    }
    calibrationTimerOpt = Some(timer)
    timer.start()
  }

  def show(): Unit = {
    val leftJoystick  = new JoystickView("Left", radius = 100)
    val rightJoystick = new JoystickView("Right", radius = 100)
    val btnCalibrate  = new Button("Calibration")
    val btnStop       = new Button("Stop")
    val btnStartRadio = new Button("Start Radio")
    val btnStartSim   = new Button("Start Simulated Radio")
    val statusLabel   = new Label("Idle")

    val sourceSelector = new ComboBox[String](ObservableBuffer("Radio", "Simulated Radio")) {
      value = "Radio"
      promptText = "Data Source"
    }

    leftJoystickOpt = Some(leftJoystick)
    rightJoystickOpt = Some(rightJoystick)
    statusLabelOpt = Some(statusLabel)
    sourceSelectorOpt = Some(sourceSelector)

    sourceSelector.onAction = _ => {
      val v = Option(sourceSelector.value()).getOrElse("Radio")
      if (v == "Radio") setInputMode(DataSource.Radio) else setInputMode(DataSource.Simulated)
    }
    setInputMode(DataSource.Radio)

    leftJoystick.onChange = { (xNorm, yNorm) =>
      if (!isCalibrating && activeSource == DataSource.Simulated) {
        val yawV = clampToRange(xNorm, yawR)
        val throttleNorm = (-yNorm + 1) / 2.0
        val throttleV = clampToRange(throttleNorm, throttleR)
        publishState(Throttle(throttleV), Yaw(yawV), last.pitch, last.roll)
      }
    }

    rightJoystick.onChange = { (xNorm, yNorm) =>
      if (!isCalibrating && activeSource == DataSource.Simulated) {
        val rollV = clampToRange(xNorm, rollR)
        val pitchV = clampToRange(-yNorm, pitchR)
        publishState(last.throttle, last.yaw, Pitch(pitchV), Roll(rollV))
      }
    }

    btnCalibrate.onAction = _ => startCalibration()
    btnStop.onAction      = _ => {
      dispatcher.unsafeRunAndForget(stopTopic.publish1(()))
      calibrationTimerOpt.foreach(_.stop()); calibrationTimerOpt = None
      isCalibrating = false
      Platform.runLater(() => {
        leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
        rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
      })
    }

    btnStartRadio.onAction = _ => {
      setInputMode(DataSource.Radio)
      dispatcher.unsafeRunAndForget(startRadioTopic.publish1(()))
    }

    btnStartSim.onAction = _ => {
      setInputMode(DataSource.Simulated)
      dispatcher.unsafeRunAndForget(startSimTopic.publish1(()))
    }

    val leftWithAxis  = axisLabeled(leftJoystick,  "Left Joystick")
    val rightWithAxis = axisLabeled(rightJoystick, "Right Joystick", yPositiveUp = true)

    val rootPane = new BorderPane {
      padding = Insets(10)
      style = "-fx-background-color: #1e1e1e;"
      center = new HBox(20) { children = Seq(leftWithAxis, rightWithAxis) }
      bottom = new VBox(10) {
        children = Seq(
          new HBox(10) {
            children = Seq(
              new Label("Source:") { style = "-fx-text-fill: white;" },
              sourceSelector,
              btnStartRadio,
              btnStartSim,
              btnCalibrate,
              btnStop
            )
          },
          statusLabel
        )
      }
    }

    new Stage() {
      title = s"scalaSegment — ${uiTheme}"
      width = uiWidth
      height = uiHeight
      scene = new Scene(rootPane)
    }.show()
  }

  override def setGimbalState(state: ControllerState): F[Unit] = F.delay {
    last = state
    if (activeSource == DataSource.Radio) {
      (leftJoystickOpt, rightJoystickOpt) match {
        case (Some(leftJoystick), Some(rightJoystick)) =>
          val yawX = normalizeFromRange(state.yaw.value, yawR)
          val throttleYNorm = 1.0 - (state.throttle.value - throttleR.min) / (throttleR.max - throttleR.min) * 2.0
          val pitchY = normalizeFromRange(state.pitch.value, pitchR)
          val rollX  = normalizeFromRange(state.roll.value, rollR)
          Platform.runLater { () =>
            leftJoystick.setNorm(yawX, throttleYNorm)
            rightJoystick.setNorm(rollX, -pitchY)
            statusLabelOpt.foreach(lbl => lbl.text = f"Throttle=${state.throttle.value}%.2f Yaw=${state.yaw.value}%.2f Pitch=${state.pitch.value}%.2f Roll=${state.roll.value}%.2f")
          }
        case _ => ()
      }
    }
  }

  private def normalizeFromRange(value: Double, r: AxisRange): Double = {
    val clipped = math.max(r.min, math.min(r.max, value))
    val span = r.max - r.min
    if (span == 0) 0.0 else (clipped - r.center) / (span / 2.0)
  }

  override def onCalibrationStart: Fs2Stream[F, Unit] = calibrateTopic.subscribe(16)
  override def onTestStart: Fs2Stream[F, Unit]        = testTopic.subscribe(16)
  override def onStop: Fs2Stream[F, Unit]             = stopTopic.subscribe(16)
}

private final class JoystickView(label: String, radius: Double) {
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
    nx = (clampedDx / max)
    ny = (clampedDy / max)
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
