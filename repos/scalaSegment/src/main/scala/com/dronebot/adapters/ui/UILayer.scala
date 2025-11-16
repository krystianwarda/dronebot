// Scala
package com.dronebot.adapters.ui

import cats.effect.{Async}
import cats.effect.std.Dispatcher
import cats.effect.kernel.Fiber
import cats.syntax.all._
import cats.effect.syntax.all._
import com.dronebot.adapters.infra.ports.UILayerPort
import com.dronebot.adapters.infra.simdroneinfo.DroneTelemetry
import com.dronebot.adapters.infra.simradio.{Calibration, UiSimRadio}
import com.dronebot.app.config.AxisRange
import com.dronebot.core.domain._
import com.dronebot.core.flightcontrol.TestFlightRunner
import fs2.concurrent.Topic
import fs2.{Stream => Fs2Stream}
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.ObservableBuffer
import scalafx.geometry._
import scalafx.scene._
import scalafx.scene.control._
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.shape.Circle
import scalafx.stage._

final class UILayerFx[F[_]](
                             dispatcher: Dispatcher[F],
                             ctrlTopic: Topic[F, ControllerState],
                             calibrateTopic: Topic[F, Unit],
                             testTopic: Topic[F, Unit],
                             stopTopic: Topic[F, Unit],
                             startRadioTopic: Topic[F, Unit],
                             startSimTopic: Topic[F, Unit],
                             startHybridTopic: Topic[F, Unit],
                             toggleAutoTop: fs2.concurrent.Topic[F, Unit],
                             throttleR: AxisRange,
                             yawR: AxisRange,
                             pitchR: AxisRange,
                             rollR: AxisRange,
                             uiWidth: Int,
                             uiHeight: Int,
                             uiTheme: String,
                             gaugeSize: Double = 150.0
                           )(implicit F: Async[F]) extends UILayerPort[F] {

  private sealed trait DataSource
  private object DataSource {
    case object Radio extends DataSource
    case object Simulated extends DataSource
  }
  @volatile private var activeSource: DataSource = DataSource.Radio

  private var gameInfoViewOpt: Option[GameDroneInfoView] = None
  private var mapViewOpt: Option[TelemetryMapView] = None
  private var altitudeViewOpt: Option[AltitudeGraphView] = None

  private var leftJoystickOpt: Option[JoystickView]  = None
  private var rightJoystickOpt: Option[JoystickView] = None
  private var statusLabelOpt: Option[Label]          = None
  private var sourceSelectorOpt: Option[ComboBox[String]] = None

  private var calibrationOpt: Option[Calibration[F]] = None
  private def calibrationIsRunning: Boolean = calibrationOpt.exists(_.isRunning)

  private var testFlightFiberOpt: Option[Fiber[F, Throwable, Unit]] = None
  private def testIsRunning: Boolean = testFlightFiberOpt.isDefined

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
    val topLbl    = new Label(topText)   { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val bottomLbl = new Label(bottomText){ style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val leftLbl   = new Label("-1")      { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val rightLbl  = new Label("1")       { style = "-fx-text-fill: white; -fx-font-size: 10px;" }
    val axisPane = new BorderPane {
      top = topLbl; bottom = bottomLbl; left = leftLbl; right = rightLbl; center = view.node
      BorderPane.setAlignment(topLbl, Pos.Center)
      BorderPane.setAlignment(bottomLbl, Pos.Center)
      BorderPane.setAlignment(leftLbl, Pos.Center)
      BorderPane.setAlignment(rightLbl, Pos.Center)
    }
    new VBox(5) { children = Seq(new Label(title) { style = "-fx-text-fill: white;" }, axisPane) }
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
    if (calibrationIsRunning) return
    val onFinish: () => Unit = () => {
      calibrationOpt = None
      Platform.runLater(() => {
        leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
        rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
      })
    }
    val cal = new Calibration[F](
      dispatcher,
      calibrateTopic,
      (lx: Double, ly: Double, rx: Double, ry: Double) => applyPositionsAndPublish(lx, ly, rx, ry),
      onFinish
    )
    calibrationOpt = Some(cal)
    cal.start()
  }

  private def startTestFlight(): Unit = {
    if (testIsRunning) return
    val onFinish: F[Unit] = F.delay {
      Platform.runLater(() => {
        leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
        rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
      })
    }
    val radio  = new UiSimRadio[F](applyPositionsAndPublish)
    val runner = new TestFlightRunner[F](radio)
    val program: F[Unit] =
      (testTopic.publish1(()).void >> runner.run())
        .guarantee(onFinish >> F.delay { testFlightFiberOpt = None })

    dispatcher.unsafeRunAndForget {
      for {
        fiber <- F.start(program)
        _     <- F.delay { testFlightFiberOpt = Some(fiber) }
      } yield ()
    }
  }

  private def stopAll(): Unit = {
    dispatcher.unsafeRunAndForget(stopTopic.publish1(()))
    calibrationOpt.foreach(_.stop()); calibrationOpt = None
    testFlightFiberOpt.foreach(_.cancel); testFlightFiberOpt = None
    Platform.runLater(() => {
      leftJoystickOpt.foreach(_.setNorm(0.0, 0.0))
      rightJoystickOpt.foreach(_.setNorm(0.0, 0.0))
    })
  }

  def show(): Unit = {
    val leftJoystick  = new JoystickView("Left", radius = 50)
    val rightJoystick = new JoystickView("Right", radius = 50)

    val gameInfoView  = new GameDroneInfoView("Drone Telemetry", gaugeSize)
    gameInfoViewOpt = Some(gameInfoView)

    val mapView = new TelemetryMapView("XY Map", size = 250.0, pixelsPerUnit = 0.5)
    val altitudeView = new AltitudeGraphView("Altitude (Z) vs time")
    mapViewOpt = Some(mapView)
    altitudeViewOpt = Some(altitudeView)

    val controlsPanel = new ControlsPanel

    leftJoystickOpt = Some(leftJoystick)
    rightJoystickOpt = Some(rightJoystick)
    statusLabelOpt = Some(controlsPanel.statusLabel)
    sourceSelectorOpt = Some(controlsPanel.sourceSelector)

    controlsPanel.btnToggleAutopilot.onAction = _ =>
      dispatcher.unsafeRunAndForget(toggleAutoTop.publish1(()))

    controlsPanel.sourceSelector.onAction = _ => {
      val v = Option(controlsPanel.sourceSelector.value()).getOrElse("Radio")
      if (v == "Radio") setInputMode(DataSource.Radio) else setInputMode(DataSource.Simulated)
    }
    setInputMode(DataSource.Radio)

    leftJoystick.onChange = { (xNorm, yNorm) =>
      if (!calibrationIsRunning && !testIsRunning && activeSource == DataSource.Simulated) {
        val yawV = clampToRange(xNorm, yawR)
        val throttleNorm = (-yNorm + 1) / 2.0
        val throttleV = clampToRange(throttleNorm, throttleR)
        publishState(Throttle(throttleV), Yaw(yawV), last.pitch, last.roll)
      }
    }
    rightJoystick.onChange = { (xNorm, yNorm) =>
      if (!calibrationIsRunning && !testIsRunning && activeSource == DataSource.Simulated) {
        val rollV = clampToRange(xNorm, rollR)
        val pitchV = clampToRange(-yNorm, pitchR)
        publishState(last.throttle, last.yaw, Pitch(pitchV), Roll(rollV))
      }
    }

    controlsPanel.btnCalibrate.onAction  = _ => startCalibration()
    controlsPanel.btnTest.onAction       = _ => startTestFlight()
    controlsPanel.btnStop.onAction       = _ => stopAll()
    controlsPanel.btnStartRadio.onAction = _ => {
      setInputMode(DataSource.Radio)
      dispatcher.unsafeRunAndForget(startRadioTopic.publish1(()))
    }
    controlsPanel.btnStartSim.onAction = _ => {
      setInputMode(DataSource.Simulated)
      dispatcher.unsafeRunAndForget(startSimTopic.publish1(()))
    }
    controlsPanel.btnStartHybrid.onAction = _ => {
      setInputMode(DataSource.Radio)
      dispatcher.unsafeRunAndForget(startHybridTopic.publish1(()))
    }

    val joysticksPanel = JoystickViewHelpers.createJoysticksPanel(leftJoystick, rightJoystick, "Joysticks")

    val rootPane = new BorderPane {
      padding = Insets(10)
      style = "-fx-background-color: -fx-background;"
      center = new VBox(10) {
        children = Seq(
          new HBox(10) { children = Seq(joysticksPanel, gameInfoView.node) },
          new HBox(10) { children = Seq(controlsPanel.node, mapView.node, altitudeView.node) }
        )
      }
    }

    new Stage() {
      title = s"scalaSegment — ${uiTheme}"
      width = math.max(uiWidth, 1280)
      height = uiHeight
      scene = new Scene(rootPane)
    }.show()
  }

  def setDroneTelemetry(t: DroneTelemetry): F[Unit] = F.delay {
    Platform.runLater(() => {
      gameInfoViewOpt.foreach(_.update(t))
      mapViewOpt.foreach(_.update(t))
      altitudeViewOpt.foreach(_.update(t))
    })
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
