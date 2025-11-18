package com.dronebot.app.runtime

import cats.effect.{IO, Ref, Resource}
import com.dronebot.adapters.simradio.VirtualGamepadPort

final class DeviceManager private (ref: Ref[IO, Option[VirtualGamepadPort[IO]]]) {
  def getOrCreate: IO[Option[VirtualGamepadPort[IO]]] =
    ref.get.flatMap {
      case s @ Some(_) => IO.pure(s)
      case None        => IO.delay(VirtualGamepadPort.trySelect[IO]).flatTap(p => ref.set(p))
    }
}

object DeviceManager {
  def resource: Resource[IO, DeviceManager] =
    Resource.eval(Ref.of[IO, Option[VirtualGamepadPort[IO]]](None)).map(new DeviceManager(_))
}
