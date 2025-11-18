
package com.dronebot.adapters.radio

import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.{ControllerState, Pitch, Roll, Throttle, Yaw}

private[radio] object SocketJson {
  private val AnsiPattern = "\u001B\\[[;?0-9]*[a-zA-Z]".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")

  // Extract complete JSON objects delimited by balanced braces from a buffer
  def extract(input: String): (List[String], String) = {
    val cleaned = stripAnsi(input)
    val out = scala.collection.mutable.ListBuffer.empty[String]
    var start = -1
    var depth = 0
    var i = 0
    while (i < cleaned.length) {
      cleaned.charAt(i) match {
        case '{' => if (depth == 0) start = i; depth += 1
        case '}' =>
          if (depth > 0) {
            depth -= 1
            if (depth == 0 && start >= 0) { out += cleaned.substring(start, i + 1); start = -1 }
          }
        case _ =>
      }
      i += 1
    }
    val remainder = if (depth == 0) "" else cleaned.substring(start.max(0))
    (out.toList, remainder)
  }

  private val num = "[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?"
  private def find(field: String, json: String): Option[Double] = {
    val r = (s""""$field"\\s*:\\s*($num)""").r
    r.findFirstMatchIn(json).flatMap(m => scala.util.Try(m.group(1).toDouble).toOption)
  }

  private def mapRange(norm: Double, min: Double, max: Double, center: Double): Double = {
    val spanHalf = (max - min) / 2.0
    val clamped  = math.max(-1.0, math.min(1.0, norm))
    center + clamped * spanHalf
  }

  // Parse a single JSON line/object into a ControllerState using configured ranges
  def parse(json: String, ranges: JoystickRanges): Option[ControllerState] = {
    val yawNorm    = find("yaw", json).getOrElse(0.0)
    val throttle01 = find("throttle", json).getOrElse(0.0)
    val pitchNorm  = find("pitch", json).getOrElse(0.0)
    val rollNorm   = find("roll", json).getOrElse(0.0)

    val throttleV = mapRange(throttle01 * 2 - 1, ranges.throttle.min, ranges.throttle.max, ranges.throttle.center)
    val yawV      = mapRange(yawNorm, ranges.yaw.min, ranges.yaw.max, ranges.yaw.center)
    val pitchV    = mapRange(pitchNorm, ranges.pitch.min, ranges.pitch.max, ranges.pitch.center)
    val rollV     = mapRange(rollNorm, ranges.roll.min, ranges.roll.max, ranges.roll.center)

    Some(ControllerState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV), System.currentTimeMillis()))
  }
}
