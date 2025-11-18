package com.dronebot.adapters.hybridsimradio

import cats.effect.Async
import cats.syntax.all._
import com.comcast.ip4s.{Host, Port, SocketAddress}
import com.dronebot.core.domain.flight.{ControllerState, Pitch, Roll, Throttle, Yaw}
import fs2.Stream
import fs2.io.net.Network

import scala.util.Try

object TcpRadioStream {

  // Expected line formats (examples):
  // CSV: 0.50,0.00,0.10,-0.20   => t,y,p,r
  // KV:  t=0.50 y=0.00 p=0.10 r=-0.20
  def stream[F[_]: Async](host: String, port: Int): Stream[F, ControllerState] = {
    val addr = SocketAddress(Host.fromString(host).get, Port.fromInt(port).get)

    Stream.resource(Network[F].client(addr)).flatMap { socket =>
      val in =
        socket.reads
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .evalMap(parseLine[F])
          .handleErrorWith { e =>
            Stream.eval(Async[F].delay(println(s"[TCP-RADIO] error: ${e.getMessage}"))).drain
          }

      Stream.eval(Async[F].delay(println(s"[TCP-RADIO] connected to $host:$port"))) *> in
    }
  }

  private def parseLine[F[_]: Async](line: String): F[ControllerState] = {
    import TcpRadioCommon._

    def fromCsv(parts: Array[String]): Option[(Double, Double, Double, Double)] =
      if (parts.length >= 4)
        Try((parts(0), parts(1), parts(2), parts(3)).map(_.trim.toDouble)).toOption
      else None

    def fromKv(s: String): Option[(Double, Double, Double, Double)] = {
      val re = raw""".*t\s*=\s*([-\d.]+).*y\s*=\s*([-\d.]+).*p\s*=\s*([-\d.]+).*r\s*=\s*([-\d.]+).*""".r
      s match {
        case re(t, y, p, r) => (t.toDouble, y.toDouble, p.toDouble, r.toDouble).some
        case _              => None
      }
    }

    val parsed: Option[(Double, Double, Double, Double)] =
      fromCsv(line.split("[,\\s]+")).orElse(fromKv(line))

    parsed match {
      case Some((t, y, p, r)) =>
        Async[F].pure(
          ControllerState(
            throttle    = Throttle(clamp01(t)),
            yaw         = Yaw(clamp11(y)),
            pitch       = Pitch(clamp11(p)),
            roll        = Roll(clamp11(r)),
            timestampMs = System.currentTimeMillis()
          )
        )
      case None =>
        Async[F].delay {
          println(s"[TCP-RADIO] unparseable line: $line")
          ControllerState(Throttle(0.0), Yaw(0.0), Pitch(0.0), Roll(0.0), System.currentTimeMillis())
        }
    }
  }

  private implicit class Tuple4Ops[A](val t: (String, String, String, String)) extends AnyVal {
    def map(f: String => A): (A, A, A, A) = (f(t._1), f(t._2), f(t._3), f(t._4))
  }
}
