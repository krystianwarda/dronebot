// Scala
package com.dronebot.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.config.JoystickRanges
import com.dronebot.domain.{ControllerState, Pitch, Roll, Throttle, Yaw}
import fs2.Stream

import java.io.InputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

/** TCP socket radio backend (client).
 * Connects to 127.0.0.1:9000 and parses noisy lines such as:
 *   Listening for messages on 127.0.0.1:9000 \u001b[K{...json...}}}}} Stopped listening.  Stopped listening.
 */
final class SocketRadio[F[_]: Async] extends RadioBackend[F] {
  private val F = Async[F]

  override def name: String = "socket"

  override def listDevices: F[List[String]] =
    F.pure(List("tcp://127.0.0.1:9000"))

  private def preview(s: String, max: Int = 200): String = {
    val sanitized = s
      .replace("\r", "\\r")
      .replace("\n", "\\n")
      .replaceAll("\\p{C}", "?")
    if (sanitized.length > max) sanitized.take(max) + "..." else sanitized
  }

  override def controllerStream(ranges: JoystickRanges): Stream[F, ControllerState] = {
    def connect: F[Socket] = F.blocking {
      val s = new Socket()
      s.connect(new InetSocketAddress("127.0.0.1", 9000), 2000)
      s.setTcpNoDelay(true)
      s
    }

    def readConn(sock: Socket): Stream[F, ControllerState] = {
      val inF: F[InputStream] = F.delay(sock.getInputStream)
      Stream.eval(inF).flatMap { in =>
        val buf = new Array[Byte](4096)
        val sb  = new StringBuilder

        def readChunk: F[Option[String]] = F.blocking {
          val n = in.read(buf)
          if (n <= 0) {
            println("[RADIO/SOCKET] EOF from server")
            None
          } else {
            val s = new String(buf, 0, n, StandardCharsets.UTF_8)
            println(s"[RADIO/SOCKET] Read ${n} bytes: ${preview(s)}")
            Some(s)
          }
        }.handleError { e =>
          println(s"[RADIO/SOCKET] Read error: ${e.getMessage}"); None
        }

        Stream
          .repeatEval(readChunk)
          .unNoneTerminate
          .flatMap { s =>
            sb.append(s)
            val (jsons, remainder) = SocketRadio.extractJsonObjects(sb.result())
            println(s"[RADIO/SOCKET] Buffer len=${sb.length} extracted=${jsons.size} remainderLen=${remainder.length}")
            sb.clear(); sb.append(remainder)

            if (jsons.nonEmpty) {
              jsons.foreach(j => println(s"[RADIO/SOCKET] JSON: ${preview(j)}"))
            }

            val states = jsons.flatMap { j =>
              val stOpt = SocketRadio.parseJsonToState(j, ranges)
              stOpt.foreach { st =>
                println(f"[RADIO/SOCKET] Parsed -> t=${st.throttle.value}%.2f y=${st.yaw.value}%.2f p=${st.pitch.value}%.2f r=${st.roll.value}%.2f")
              }
              stOpt
            }

            if (states.isEmpty) Stream.empty
            else Stream.emits(states)
          }
      }
    }

    val single: Stream[F, ControllerState] =
      Stream.eval(F.delay(println("[RADIO/SOCKET] Connecting to 127.0.0.1:9000..."))) >>
        Stream.bracket(connect)(sock => F.delay(sock.close()).handleError(_ => ())).flatMap { sock =>
            Stream.eval(F.delay(println(s"[RADIO/SOCKET] Connected (local ${sock.getLocalPort})"))) >>
              readConn(sock)
          }.onFinalize(F.delay(println("[RADIO/SOCKET] Disconnected")))
          .handleErrorWith { e =>
            Stream.eval(F.delay(println(s"[RADIO/SOCKET] Connection error: ${e.getMessage}; retrying in 1s"))) >>
              Stream.sleep[F](1.second) >>
              Stream.empty
          }

    single.repeat
  }
}

private object SocketRadio {
  // Extract well-formed JSON objects from noisy text by brace balancing.
  def extractJsonObjects(input: String): (List[String], String) = {
    val cleaned = stripAnsi(input)
    val out     = scala.collection.mutable.ListBuffer.empty[String]
    var start   = -1
    var depth   = 0
    var i       = 0
    while (i < cleaned.length) {
      cleaned.charAt(i) match {
        case '{' =>
          if (depth == 0) start = i
          depth += 1
        case '}' =>
          if (depth > 0) {
            depth -= 1
            if (depth == 0 && start >= 0) {
              out += cleaned.substring(start, i + 1)
              start = -1
            }
          }
        case _ =>
      }
      i += 1
    }
    val remainder =
      if (depth == 0) ""
      else cleaned.substring(start.max(0))
    (out.toList, remainder)
  }

  private val AnsiPattern = "\u001B\\[[;?0-9]*[a-zA-Z]".r
  private def stripAnsi(s: String): String =
    AnsiPattern.replaceAllIn(s, "")

  private val num = "[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?"
  private def findDouble(field: String, json: String): Option[Double] = {
    val r = (s""""$field"\\s*:\\s*($num)""").r
    r.findFirstMatchIn(json).flatMap(m => scala.util.Try(m.group(1).toDouble).toOption)
  }

  // Map normalized [-1,1] value to configured range
  private def mapRange(norm: Double, min: Double, max: Double, center: Double): Double = {
    val spanHalf = (max - min) / 2.0
    val clamped  = math.max(-1.0, math.min(1.0, norm))
    center + clamped * spanHalf
  }

  def parseJsonToState(json: String, ranges: com.dronebot.config.JoystickRanges): Option[ControllerState] = {
    val yawNorm      = findDouble("yaw", json).getOrElse(0.0)
    val throttle01   = findDouble("throttle", json).getOrElse(0.0)
    val pitchNorm    = findDouble("pitch", json).getOrElse(0.0)
    val rollNorm     = findDouble("roll", json).getOrElse(0.0)

    val throttleV = mapRange(throttle01 * 2 - 1, ranges.throttle.min, ranges.throttle.max, ranges.throttle.center)
    val yawV      = mapRange(yawNorm, ranges.yaw.min, ranges.yaw.max, ranges.yaw.center)
    // FIX: remove parser inversion; keep sign as-is (UI already inverts for display)
    val pitchV    = mapRange(pitchNorm, ranges.pitch.min, ranges.pitch.max, ranges.pitch.center)
    val rollV     = mapRange(rollNorm, ranges.roll.min, ranges.roll.max, ranges.roll.center)

    Some(ControllerState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV), System.currentTimeMillis()))
  }

}
