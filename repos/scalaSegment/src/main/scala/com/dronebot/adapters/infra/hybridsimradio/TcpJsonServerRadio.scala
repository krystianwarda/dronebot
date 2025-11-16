// Scala
package com.dronebot.adapters.infra.hybridsimradio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.core.domain._
import fs2.Stream
import fs2.io.net.Network
import com.comcast.ip4s.{Host, Port}
import scala.util.matching.Regex

object TcpJsonServerRadio {

  def stream[F[_]: Async](bindHost: String, bindPort: Int): Stream[F, ControllerState] = {
    val host = Host.fromString(bindHost).get
    val port = Port.fromInt(bindPort).get

    val bindLog = Stream.eval(Async[F].delay(println(s"[TCP-SERVER] binding on $bindHost:$bindPort")))

    val server =
      Network[F].server(address = Some(host), port = Some(port)).flatMap { socket =>
        val announce =
          Stream.eval(
            socket.remoteAddress.flatMap(ra => Async[F].delay(println(s"[TCP-SERVER] client connected: $ra")))
          )

        val in =
          socket.reads
            .through(fs2.text.utf8.decode)
            .through(fs2.text.lines)
            .evalMap(parseJsonLine[F])
            .handleErrorWith { e =>
              Stream.eval(Async[F].delay(println(s"[TCP-SERVER] error: ${e.getMessage}"))).drain
            }

        announce >> in
      }

    bindLog >> server
  }

  private def parseJsonLine[F[_]: Async](line: String): F[ControllerState] = {
    def clamp01(x: Double): Double = math.max(0.0, math.min(1.0, x))
    def clamp11(x: Double): Double = math.max(-1.0, math.min(1.0, x))

    def findNum(keys: List[String], s: String): Option[Double] = {
      val num = """[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"""
      val patterns: List[Regex] = keys.map { k =>
        (s""""$k"\\s*:\\s*($num)""").r
      }
      patterns.iterator
        .flatMap(_.findFirstMatchIn(s).map(_.group(1)))
        .toSeq
        .headOption
        .map(_.toDouble)
    }

    val tOpt = findNum(List("t", "throttle"), line)
    val yOpt = findNum(List("y", "yaw"), line)
    val pOpt = findNum(List("p", "pitch"), line)
    val rOpt = findNum(List("r", "roll"), line)

    (tOpt, yOpt, pOpt, rOpt) match {
      case (Some(t), Some(y), Some(p), Some(r)) =>
        Async[F].pure(
          ControllerState(
            throttle    = Throttle(clamp01(t)),
            yaw         = Yaw(clamp11(y)),
            pitch       = Pitch(clamp11(p)),
            roll        = Roll(clamp11(r)),
            timestampMs = System.currentTimeMillis()
          )
        )
      case _ =>
        Async[F].delay {
          println(s"[TCP-SERVER] unparseable JSON: $line")
          ControllerState(Throttle(0.0), Yaw(0.0), Pitch(0.0), Roll(0.0), System.currentTimeMillis())
        }
    }
  }
}
