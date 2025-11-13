package com.dronebot.adapters.infra.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain._
import fs2.Stream

import java.io.InputStream
import java.net.{BindException, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

final class TcpServerRadio[F[_]: Async] extends RadioBackend[F] {
  private val F = Async[F]
  private val Host = "127.0.0.1"
  private val Port = 9000

  override def name: String = "tcp-server"
  override def listDevices: F[List[String]] =
    F.pure(List(s"tcp://$Host:$Port (listening)"))

  override def controllerStream(ranges: JoystickRanges): Stream[F, ControllerState] = {
    def openServer: F[ServerSocket] = F.blocking {
      println(s"[RADIO/TCP-SERVER] Binding tcp://$Host:$Port ...")
      val ss = new ServerSocket()
      ss.setReuseAddress(true)
      try {
        ss.bind(new InetSocketAddress(Host, Port))
      } catch {
        case e: BindException =>
          println(s"[RADIO/TCP-SERVER] Bind failed: ${e.getMessage} (is something already using :$Port?)")
          ss.close()
          throw e
      }
      ss
    }

    def closeServer(ss: ServerSocket): F[Unit] =
      F.delay(ss.close()).handleError(_ => ())

    def accept(ss: ServerSocket): F[Socket] = F.blocking(ss.accept())
    def closeClient(s: Socket): F[Unit] = F.delay(s.close()).handleError(_ => ())

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
          if (n <= 0) { println("[RADIO/TCP-SERVER] Client EOF"); None }
          else {
            val s = new String(buf, 0, n, StandardCharsets.UTF_8)
            println(s"[RADIO/TCP-SERVER] Read ${n} bytes: ${preview(s)}")
            Some(s)
          }
        }.handleError { e =>
          println(s"[RADIO/TCP-SERVER] Read error: ${e.getMessage}"); None
        }

        Stream
          .repeatEval(readChunk)
          .unNoneTerminate
          .flatMap { chunk =>
            sb.append(chunk)

            // newline-delimited first
            val statesFromLines = {
              var states = List.empty[ControllerState]
              var idx = sb.indexOf("\n")
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

            // fallback brace-balanced
            val statesFromBraces = {
              val (jsons, remainder) = SocketJson.extractJsonObjects(sb.result())
              if (jsons.nonEmpty) println(s"[RADIO/TCP-SERVER] Extracted ${jsons.size} JSON object(s) by braces")
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

    // Keep trying to open the server; only prints "Listening" after successful bind
    def serverLoop: Stream[F, ControllerState] =
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

    serverLoop.handleErrorWith { e =>
      Stream.eval(F.delay(println(s"[RADIO/TCP-SERVER] Fatal server error: ${e.getMessage}; retrying in 2s"))) >>
        Stream.sleep[F](2.seconds) >>
        serverLoop
    }
  }
}

private object SocketJson {
  private val AnsiPattern = "\u001B\\[[;?0-9]*[a-zA-Z]".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")

  def extractJsonObjects(input: String): (List[String], String) = {
    val cleaned = stripAnsi(input)
    val out     = scala.collection.mutable.ListBuffer.empty[String]
    var start   = -1
    var depth   = 0
    var i       = 0
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
  private def findDouble(field: String, json: String): Option[Double] = {
    val r = (s""""$field"\\s*:\\s*($num)""").r
    r.findFirstMatchIn(json).flatMap(m => scala.util.Try(m.group(1).toDouble).toOption)
  }

  private def mapRange(norm: Double, min: Double, max: Double, center: Double): Double = {
    val spanHalf = (max - min) / 2.0
    val clamped  = math.max(-1.0, math.min(1.0, norm))
    center + clamped * spanHalf
  }

  def parseJsonToState(json: String, ranges: JoystickRanges): Option[ControllerState] = {
    val yawNorm      = findDouble("yaw", json).getOrElse(0.0)
    val throttle01   = findDouble("throttle", json).getOrElse(0.0)
    val pitchNorm    = findDouble("pitch", json).getOrElse(0.0)
    val rollNorm     = findDouble("roll", json).getOrElse(0.0)

    val throttleV = mapRange(throttle01 * 2 - 1, ranges.throttle.min, ranges.throttle.max, ranges.throttle.center)
    val yawV      = mapRange(yawNorm, ranges.yaw.min, ranges.yaw.max, ranges.yaw.center)
    val pitchV    = mapRange(pitchNorm, ranges.pitch.min, ranges.pitch.max, ranges.pitch.center)
    val rollV     = mapRange(rollNorm, ranges.roll.min, ranges.roll.max, ranges.roll.center)

    Some(ControllerState(Throttle(throttleV), Yaw(yawV), Pitch(pitchV), Roll(rollV), System.currentTimeMillis()))
  }
}
