// Scala
package com.dronebot.core.flightcontrol.planner

import com.dronebot.adapters.simdroneinfo.Vec3


final case class DroneState(stepMeters: Double)
/**
 * Pure flight planner with a hardcoded test flight and a pure sub-waypoint generator.
 */
final class TestFlight {

  /**
   * Hardcoded plan:
   *   1) z + 10
   *   2) x + 100
   *   3) y + 100
   *   4) x - 100
   *   5) y - 100
   *   6) z - 10 (back to the original z)
   */
  def testFlight1(start: Vec3): List[Vec3] = {
    val p1 = start.copy(z = start.z + 10)
    val p2 = p1.copy(x = p1.x + 100)
    val p3 = p2.copy(y = p2.y + 100)
    val p4 = p3.copy(x = p3.x - 100)
    val p5 = p4.copy(y = p4.y - 100)
    val p6 = p5.copy(z = start.z)
    List(p1, p2, p3, p4, p5, p6)
  }

  /**
   * Pure sub-waypoint generator between the current position and the target waypoint.
   * It yields a lazy, finite stream (LazyList) of intermediate points including the target.
   */
  def subWaypoints(current: Vec3, target: Vec3, droneState: DroneState): LazyList[Vec3] =
    TestFlight.subWaypoints(current, target, droneState)
}

object TestFlight {
  private def minus(a: Vec3, b: Vec3): Vec3 = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
  private def plus(a: Vec3, b: Vec3): Vec3  = Vec3(a.x + b.x, a.y + b.y, a.z + b.z)
  private def scale(v: Vec3, s: Double): Vec3 = Vec3(v.x * s, v.y * s, v.z * s)
  private def norm(v: Vec3): Double = math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

  def subWaypoints(current: Vec3, target: Vec3, droneState: DroneState): LazyList[Vec3] = {
    val to      = minus(target, current)
    val dist    = norm(to)
    val stepLen = math.max(droneState.stepMeters, 1e-9)

    if (dist <= 0.0) LazyList.empty
    else {
      val dir = scale(to, 1.0 / dist)

      def loop(p: Vec3): LazyList[Vec3] = {
        val remaining = minus(target, p)
        val remDist   = norm(remaining)
        if (remDist <= stepLen) LazyList(target)
        else {
          val next = plus(p, scale(dir, stepLen))
          next #:: loop(next)
        }
      }

      loop(current)
    }
  }
}
