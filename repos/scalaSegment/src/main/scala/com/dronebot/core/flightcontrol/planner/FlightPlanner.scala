package com.dronebot.core.flightcontrol.planner

import cats.Applicative
import cats.syntax.all._
import com.dronebot.adapters.simdroneinfo.Vec3

/** Identifier of a test flight selected from UI dropdown. */
sealed trait TestFlightId extends Product with Serializable
object TestFlightId {
  case object Map1CircleAround   extends TestFlightId
  case object Map2ThroughPoints  extends TestFlightId
}

/** A basic waypoint in world space (can be extended later with yaw, speed, etc.). */
final case class Waypoint(
                           id: String,
                           position: Vec3
                         )

/** Single logical segment of a flight plan. */
sealed trait FlightSegment extends Product with Serializable {
  def startTimeSec: Double
  def endTimeSec: Double
}
object FlightSegment {

  /** Keep position around a waypoint. */
  final case class HoldPosition(
                                 wp: Waypoint,
                                 startTimeSec: Double = 0.0,
                                 endTimeSec: Double = Double.PositiveInfinity
                               ) extends FlightSegment

  /** Move from `from` to `to` between times. */
  final case class StraightLine(
                                 from: Waypoint,
                                 to: Waypoint,
                                 startTimeSec: Double,
                                 endTimeSec: Double
                               ) extends FlightSegment

  /** Simple circle around a center. */
  final case class CircleAround(
                                 center: Waypoint,
                                 radiusMeters: Double,
                                 centerZ: Option[Double],
                                 startTimeSec: Double,
                                 endTimeSec: Double
                               ) extends FlightSegment
}

/** Entire flight plan that an autopilot can follow. */
final case class FlightPlan(
                             id: TestFlightId,
                             start: Waypoint,
                             segments: List[FlightSegment]
                           )

object FlightPlanLibrary {

  /** Pure constructor of known plans. */
  def byId(id: TestFlightId): Option[FlightPlan] =
    id match {
      case TestFlightId.Map1CircleAround  => Some(map1CircleAround)
      case TestFlightId.Map2ThroughPoints => Some(map2ThroughPoints)
    }

  private val map1CircleAround: FlightPlan = {
    val center = Waypoint("center", Vec3(0.0f, 0.0f, 15.0f))
    val seg = FlightSegment.CircleAround(
      center        = center,
      radiusMeters  = 20.0,
      centerZ       = Some(15.0),
      startTimeSec  = 0.0,
      endTimeSec    = 60.0
    )
    FlightPlan(
      id       = TestFlightId.Map1CircleAround,
      start    = center,
      segments = List(seg)
    )
  }

  private val map2ThroughPoints: FlightPlan = {
    val a = Waypoint("A", Vec3(0.0f, 0.0f, 10.0f))
    val b = Waypoint("B", Vec3(30.0f, 0.0f, 12.0f))
    val c = Waypoint("C", Vec3(30.0f, 30.0f, 14.0f))
    val d = Waypoint("D", Vec3(0.0f, 30.0f, 10.0f))

    val seg1 = FlightSegment.StraightLine(a, b, startTimeSec = 0.0,  endTimeSec = 20.0)
    val seg2 = FlightSegment.StraightLine(b, c, startTimeSec = 20.0, endTimeSec = 40.0)
    val seg3 = FlightSegment.StraightLine(c, d, startTimeSec = 40.0, endTimeSec = 60.0)

    FlightPlan(
      id       = TestFlightId.Map2ThroughPoints,
      start    = a,
      segments = List(seg1, seg2, seg3)
    )
  }
}


trait FlightPlanner[F[_]] {
  def listAvailable: F[List[TestFlightId]]
  def getPlan(id: TestFlightId): F[Option[FlightPlan]]
}

object FlightPlanner {
  def inMemory[F[_]: Applicative]: FlightPlanner[F] =
    new FlightPlanner[F] {
      private val allIds = List[TestFlightId](
        TestFlightId.Map1CircleAround,
        TestFlightId.Map2ThroughPoints
      )

      override def listAvailable: F[List[TestFlightId]] =
        allIds.pure[F]

      override def getPlan(id: TestFlightId): F[Option[FlightPlan]] =
        FlightPlanLibrary.byId(id).pure[F]
    }
}
