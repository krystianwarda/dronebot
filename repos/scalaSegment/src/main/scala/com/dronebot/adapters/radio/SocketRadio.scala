// Scala
package com.dronebot.adapters.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.ports.inbound.ControlInputPort
import fs2.Stream

import java.io.InputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

final class SocketRadio[F[_]: Async](
                                      ranges: JoystickRanges,
                                      host: String = "127.0.0.1",
                                      port: Int = 9000
                                    ) extends ControlInputPort[F] {

  private val F = Async[F]

  // Kept as a helper/alias if other code already uses it
  def controllerStream: Stream[F, ControllerState] = {
    def connect: F[Socket] = F.blocking {
      val s = new Socket()
      s.connect(new InetSocketAddress(host, port), 2000)
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
          if (n <= 0) None else Some(new String(buf, 0, n, StandardCharsets.UTF_8))
        }.handleError(_ => None)

        Stream
          .repeatEval(readChunk)
          .unNoneTerminate
          .flatMap { s =>
            sb.append(s)
            val (jsons, remainder) = SocketJson.extract(sb.result())
            sb.clear(); sb.append(remainder)
            val states = jsons.flatMap(j => SocketJson.parse(j, ranges))
            if (states.isEmpty) Stream.empty else Stream.emits(states)
          }
      }
    }

    val single =
      Stream
        .bracket(connect)(s => F.delay(s.close()).handleError(_ => ()))
        .flatMap(sock => readConn(sock))
        .handleErrorWith(_ => Stream.sleep[F](1.second) >> Stream.empty)

    single.repeat
  }
}
