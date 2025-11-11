// Scala
package com.dronebot.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.config.JoystickRanges
import com.dronebot.domain.{ControllerState, Pitch, Roll, Throttle, Yaw}
import fs2.Stream

import java.io.InputStream
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets

final class TcpServerRadio[F[_]: Async] extends RadioBackend[F] {
  private val F = Async[F]
  private val Host = "127.0.0.1"
  private val Port = 9000

  override def name: String = "tcp-server"

  override def listDevices: F[List[String]] =
    F.pure(List(s"tcp://$Host:$Port (listening)"))

  override def controllerStream(ranges: JoystickRanges): Stream[F, ControllerState] = {
    def openServer: F[ServerSocket] = F.blocking {
      val ss = new ServerSocket()
      ss.setReuseAddress(true)
      ss.bind(new InetSocketAddress(Host, Port))
      ss
    }

    def closeServer(ss: ServerSocket): F[Unit] =
      F.delay(ss.close()).handleError(_ => ())

    def accept(ss: ServerSocket): F[Socket] = F.blocking(ss.accept())

    def closeClient(s: Socket): F[Unit] =
      F.delay(s.close()).handleError(_ => ())

    def preview(s: String, max: Int = 200): String = {
      val sanitized = s.replace("\r", "\\r").replace("\n", "\\n").replaceAll("\\p{C}", "?")
      if (sanitized.length > max) sanitized.take(max) + "..." else sanitized
    }

    def readConn(sock: Socket): Stream[F, ControllerState] = {
      val inF: F[InputStream] = F.delay(sock.getInputStream)
      Stream.eval(inF).flatMap { in =>
        val buf = new Array[Byte](4096)
        val sb  = new StringBuilder

        def readChunk: F[Option[String]] = F.blocking {
          val n = in.read(buf)
          if (n <= 0) {
            println("[RADIO/TCP-SERVER] Client EOF")
            None
          } else {
            val s = new String(buf, 0, n, StandardCharsets.UTF_8)
            println(s"[RADIO/TCP-SERVER] Read ${n} bytes: ${preview(s)}")
            Some(s)
          }
        }.handleError { e =>
          println(s"[RADIO/TCP-SERVER] Read error: ${e.getMessage}")
          None
        }

        Stream
          .repeatEval(readChunk)
          .unNoneTerminate
          .flatMap { chunk =>
            sb.append(chunk)

            // Prefer newline-delimited JSON (as in your Python test)
            val statesFromLines = {
              var states   = List.empty[ControllerState]
              var idx      = sb.indexOf("\n")
              while (idx >= 0) {
                val line = sb.substring(0, idx).trim
                sb.delete(0, idx + 1)
                if (line.nonEmpty) {
                  SocketJson.parseJsonToState(line, ranges).foreach { st =>
                    println(f"[RADIO/TCP-SERVER] Parsed (NL) -> t=${st.throttle.value}%.2f y=${st.yaw.value}%.2f p=${st.pitch.value}%.2f r=${st.roll.value}%.2f")
                    states = st :: states
                  }
                }
                idx = sb.indexOf("\n")
              }
              states.reverse
            }

            // Fallback: extract complete JSON objects by brace matching
            val statesFromBraces = {
              val (jsons, remainder) = SocketJson.extractJsonObjects(sb.result())
              if (jsons.nonEmpty) {
                println(s"[RADIO/TCP-SERVER] Extracted ${jsons.size} JSON object(s) by braces")
              }
              sb.clear(); sb.append(remainder)
              jsons.flatMap { j =>
                println(s"[RADIO/TCP-SERVER] JSON: ${preview(j)}")
                SocketJson.parseJsonToState(j, ranges).map { st =>
                  println(f"[RADIO/TCP-SERVER] Parsed (BR) -> t=${st.throttle.value}%.2f y=${st.yaw.value}%.2f p=${st.pitch.value}%.2f r=${st.roll.value}%.2f")
                  st
                }
              }
            }

            val all = statesFromLines ++ statesFromBraces
            if (all.isEmpty) Stream.empty else Stream.emits(all)
          }
      }
    }

    // Server lifecycle and accept loop
    Stream.bracket(openServer)(closeServer).flatMap { ss =>
      Stream.eval(F.delay(println(s"[RADIO/TCP-SERVER] Listening on tcp://$Host:$Port"))) >>
        Stream
          .repeatEval(accept(ss).attempt)
          .flatMap {
            case Right(sock) =>
              val remote = s"${sock.getInetAddress.getHostAddress}:${sock.getPort}"
              val local  = s"${sock.getLocalAddress.getHostAddress}:${sock.getLocalPort}"
              Stream.eval(F.delay(println(s"[RADIO/TCP-SERVER] Client connected from $remote (local $local)"))) >>
                readConn(sock).onFinalize(closeClient(sock) >> F.delay(println("[RADIO/TCP-SERVER] Client disconnected")))
            case Left(e) =>
              Stream.eval(F.delay(println(s"[RADIO/TCP-SERVER] Accept error: ${e.getMessage}"))).drain
          }
    }
  }
}

private object SocketJson {
  private val AnsiPattern = "\u001B\\[[;?0-9]*[a-zA-Z]".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")

  // Brace-balanced extractor for noisy streams
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
    val remainder = if (depth == 0) "" else cleaned.substring(start.max(0))
    (out.toList, remainder)
  }

  private val num = "[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?"
  private def findDouble(field: String, json: String): Option[Double] = {
    val r = (s""""$field"\\s*:\\s*($num)""").r
    r.findFirstMatchIn(json).flatMap(m => scala.util.Try(m.group(1).toDouble).toOption)
  }

  private def mapRange(norm: Double, min: Double, max: Double, center: Double): Double = {
    val spanHalf = (max - min) / 2.0
    val clamped  = math.max(-1.0, math.min(1.0, norm))
    center + clamped * spanHalf
  }

  def parseJsonToState(json: String, ranges: com.dronebot.config.JoystickRanges): Option[ControllerState] = {
    import com.dronebot.domain._
    val yawNorm      = findDouble("yaw", json).getOrElse(0.0)       // [-1,1]
    val throttle01   = findDouble("throttle", json).getOrElse(0.0)  // [0,1]
    val pitchNorm    = findDouble("pitch", json).getOrElse(0.0)     // [-1,1]
    val rollNorm     = findDouble("roll", json).getOrElse(0.0)      // [-1,1]

    val throttleV = mapRange(throttle01 * 2 - 1, ranges.throttle.min, ranges.throttle.max, ranges.throttle.center)
    val yawV      = mapRange(yawNorm, ranges.yaw.min, ranges.yaw.max, ranges.yaw.center)
    // FIX: remove parser inversion; keep sign as-is (UI already inverts for display)
    val pitchV    = mapRange(pitchNorm, ranges.pitch.min, ranges.pitch.max, ranges.pitch.center)
    val rollV     = mapRange(rollNorm, ranges.roll.min, ranges.roll.max, ranges.roll.center)

    Some(ControllerState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV), System.currentTimeMillis()))
  }

}
