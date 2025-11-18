package com.dronebot.adapters.radio

import cats.effect.Async
import cats.syntax.all._
import com.dronebot.app.config.JoystickRanges
import com.dronebot.core.domain.flight.ControllerState
import com.dronebot.core.ports.inbound.ControlInputPort
import fs2.Stream

import java.io.InputStream
import java.net.{BindException, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

final class TcpServerRadio[F[_]: Async](
                                         ranges: JoystickRanges,
                                         host: String = "127.0.0.1",
                                         port: Int = 9000
                                       ) extends ControlInputPort[F] {

  private val F = Async[F]

  // Kept as a helper/alias if other code already uses it
  def controllerStream: Stream[F, ControllerState] = {
    def openServer: F[ServerSocket] = F.blocking {
      val ss = new ServerSocket()
      ss.setReuseAddress(true)
      try ss.bind(new InetSocketAddress(host, port))
      catch {
        case e: BindException =>
          ss.close()
          throw e
      }
      ss
    }

    def accept(ss: ServerSocket): F[Socket] = F.blocking(ss.accept())
    def closeServer(ss: ServerSocket): F[Unit] = F.delay(ss.close()).handleError(_ => ())
    def closeClient(s: Socket): F[Unit] = F.delay(s.close()).handleError(_ => ())

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
          .flatMap { chunk =>
            sb.append(chunk)

            val fromLines = {
              var out = List.empty[ControllerState]
              var idx = sb.indexOf("\n")
              while (idx >= 0) {
                val line = sb.substring(0, idx).trim
                sb.delete(0, idx + 1)
                if (line.nonEmpty) SocketJson.parse(line, ranges).foreach(st => out = st :: out)
                idx = sb.indexOf("\n")
              }
              out.reverse
            }

            val fromBraces = {
              val (jsons, remainder) = SocketJson.extract(sb.result())
              sb.clear(); sb.append(remainder)
              jsons.flatMap(j => SocketJson.parse(j, ranges))
            }

            val all = fromLines ++ fromBraces
            if (all.isEmpty) Stream.empty else Stream.emits(all)
          }
      }
    }

    def serverLoop: Stream[F, ControllerState] =
      Stream.bracket(openServer)(closeServer).flatMap { ss =>
        Stream
          .repeatEval(accept(ss).attempt)
          .flatMap {
            case Right(sock) => readConn(sock).onFinalize(closeClient(sock))
            case Left(_)     => Stream.empty
          }
      }

    serverLoop.handleErrorWith { _ =>
      Stream.sleep[F](2.seconds) >> serverLoop
    }
  }
}
