// Scala
package com.dronebot.core.flightcontrol.fsm

import com.dronebot.core.flightcontrol.planner.TestFlightId

sealed trait FlightState extends Product with Serializable
object FlightState {
  case object Idle        extends FlightState
  case object PreFlight   extends FlightState
  case object InTestFlight extends FlightState
  case object Aborted     extends FlightState
}

sealed trait FlightEvent extends Product with Serializable
object FlightEvent {
  final case class StartTestFlight(id: TestFlightId) extends FlightEvent
  case object PreFlightOk                            extends FlightEvent
  case object PreFlightFailed                        extends FlightEvent
  case object Completed                              extends FlightEvent
  case object AbortedByUser                          extends FlightEvent
}

sealed trait FlightAction extends Product with Serializable
object FlightAction {
  case object None                                   extends FlightAction
  final case class BeginTestFlight(id: TestFlightId) extends FlightAction
  case object StopAll                                extends FlightAction
}


object FlightStateMachine {

  final case class Transition(
                               next: FlightState,
                               action: FlightAction
                             )

  def step(current: FlightState, event: FlightEvent): Transition =
    (current, event) match {
      case (FlightState.Idle, FlightEvent.StartTestFlight(id)) =>
        Transition(FlightState.PreFlight, FlightAction.BeginTestFlight(id))

      case (FlightState.PreFlight, FlightEvent.PreFlightOk) =>
        Transition(FlightState.InTestFlight, FlightAction.None)

      case (FlightState.PreFlight, FlightEvent.PreFlightFailed) =>
        Transition(FlightState.Aborted, FlightAction.StopAll)

      case (FlightState.InTestFlight, FlightEvent.Completed) =>
        Transition(FlightState.Idle, FlightAction.StopAll)

      case (_, FlightEvent.AbortedByUser) =>
        Transition(FlightState.Aborted, FlightAction.StopAll)

      case (s, _) =>
        Transition(s, FlightAction.None)
    }
}
