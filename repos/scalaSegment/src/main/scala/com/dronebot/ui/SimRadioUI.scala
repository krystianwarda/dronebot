package com.dronebot.ui

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.dronebot.app.config.JoystickRanges
import com.dronebot.adapters.simradio.SimRadioControlInput
import com.dronebot.core.domain.flight._
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.{Button, Label}
import scalafx.scene.layout.{BorderPane, HBox, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape.{Circle, Rectangle}
import scalafx.scene.Group
import scalafx.stage.Stage

/** Simple ScalaFX UI for the simulated radio.
 *  It talks only to SimRadioControlInput and never to core directly.
 */
final class SimRadioUI[F[_]](
                              adapter: SimRadioControlInput[F],
                              ranges: JoystickRanges
                            )(implicit F: Async[F], dispatcher: Dispatcher[F]) {

  private val stickRadius  = 80.0
  private val knobRadius   = 10.0
  private val dragLimit    = stickRadius

  private var leftX:  Double = 0.0  // yaw
  private var leftY:  Double = 0.0  // throttle (normalized 0..1 from -1..1)
  private var rightX: Double = 0.0  // roll
  private var rightY: Double = 0.0  // pitch

  private def toNorm(d: Double): Double =
    math.max(-1.0, math.min(1.0, d / dragLimit))

  private def publish(): Unit = {
    // Map normalized \[-1,1] to axis ranges from config
    val yawV       = ranges.yaw.min + (leftX + 1.0) / 2.0 * (ranges.yaw.max - ranges.yaw.min)
    val throttleNv = (leftY + 1.0) / 2.0 // -1..1 -> 0..1
    val throttleV  = ranges.throttle.min + throttleNv * (ranges.throttle.max - ranges.throttle.min)
    val rollV      = ranges.roll.min + (rightX + 1.0) / 2.0 * (ranges.roll.max - ranges.roll.min)
    val pitchV     = ranges.pitch.min + (rightY + 1.0) / 2.0 * (ranges.pitch.max - ranges.pitch.min)

    val state = ControllerState(
      throttle   = Throttle(throttleV),
      yaw        = Yaw(yawV),
      pitch      = Pitch(pitchV),
      roll       = Roll(rollV),
      timestampMs = System.currentTimeMillis()
    )
    dispatcher.unsafeRunAndForget(adapter.publishControl(state))
  }

  private def buildStick(knob: Circle, onUpdate: (Double, Double) => Unit): VBox = {
    val base = new Circle {
      radius = stickRadius
      fill = Color.LightGray
      stroke = Color.DarkGray
    }

    val container = new VBox {
      alignment = Pos.Center
      children = Seq(
        new Group {
          children = Seq(base, knob)
        }
      )
    }

    knob.onMousePressed = e => {
      knob.userData = (e.getSceneX, e.getSceneY)
    }

    knob.onMouseDragged = e => {
      val (sx: Double, sy: Double) = knob.userData match {
        case (x: Double, y: Double) => (x, y)
        case _                      => (e.getSceneX, e.getSceneY)
      }
      val dx = e.getSceneX - sx
      val dy = e.getSceneY - sy
      val nx = math.max(-dragLimit, math.min(dragLimit, dx))
      val ny = math.max(-dragLimit, math.min(dragLimit, dy))
      knob.translateX = nx
      knob.translateY = ny
      onUpdate(toNorm(nx), toNorm(-ny))
      publish()
    }

    knob.onMouseReleased = _ => {
      knob.translateX = 0.0
      knob.translateY = 0.0
      onUpdate(0.0, 0.0)
      publish()
    }

    container
  }

  def show(primaryStage: Stage): Unit = {
    val leftKnob = new Circle {
      radius = knobRadius
      fill = Color.Red
    }

    val rightKnob = new Circle {
      radius = knobRadius
      fill = Color.Blue
    }

    val leftStick  = buildStick(leftKnob,  (x, y) => { leftX  = x; leftY  = y })
    val rightStick = buildStick(rightKnob, (x, y) => { rightX = x; rightY = y })

    val buttons = new HBox {
      spacing = 10
      alignment = Pos.Center
      children = Seq(
        new Button("Calibrate") {
          onAction = _ => dispatcher.unsafeRunAndForget(adapter.triggerCalibrate())
        },
        new Button("Test") {
          onAction = _ => dispatcher.unsafeRunAndForget(adapter.triggerTest())
        },
        new Button("Stop") {
          onAction = _ => dispatcher.unsafeRunAndForget(adapter.triggerStop())
        }
      )
    }

    val rootPane = new BorderPane {
      padding = Insets(10)
      center = new HBox {
        spacing = 40
        alignment = Pos.Center
        children = Seq(
          new VBox {
            alignment = Pos.Center
            spacing = 5
            children = Seq(new Label("Left stick"), leftStick)
          },
          new VBox {
            alignment = Pos.Center
            spacing = 5
            children = Seq(new Label("Right stick"), rightStick)
          }
        )
      }
      bottom = buttons
    }

    primaryStage.title = "Simulated Radio"
    primaryStage.scene = new Scene(rootPane, 600, 400)
    primaryStage.show()
  }
}
