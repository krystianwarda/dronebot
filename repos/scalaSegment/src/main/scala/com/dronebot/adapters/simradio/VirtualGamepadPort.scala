
package com.dronebot.adapters.simradio

import cats.effect.Sync
import com.dronebot.core.domain.flight.ControlCommand

import java.io.File
import redlaboratory.jvjoyinterface.VJoy


/** Virtual gamepad backend based on vJoy.
 *
 * This adapter maps normalized control commands to vJoy axes:
 *  - yaw      -> axis 0x30
 *  - throttle -> axis 0x31 (with scaling `1.0 - 2.0 * throttle` to invert range)
 *  - roll     -> axis 0x32
 *  - pitch    -> axis 0x33 (inverted)
 *
 * Values are clamped to \[-1.0, 1.0\] and converted to the vJoy integer range before
 * sending. If the vJoy DLLs cannot be loaded or device id 1 cannot be acquired,
 * `select` falls back to a no-op implementation.
 */


trait VirtualGamepadPort[F[_]] {
  def send(cmd: ControlCommand): F[Unit]
}

object VirtualGamepadPort {
  private[this] var announced = false
  private def announce(msg: String): Unit = if (!announced) { println(msg); announced = true }

  private def dllRoot  = new File("C:\\tools\\DLLs")
  private def dll64Dir = new File(dllRoot, "64")
  private def vjoyDll  = new File(dll64Dir, "vJoyInterface.dll")
  private def jniDll   = new File(dllRoot, "libJvJoyInterfaceNative.dll")

  private def preloadNative(): Unit = {
    println(s"[vpad] java.library.path = ${System.getProperty("java.library.path")}")
    Seq(vjoyDll, jniDll).foreach { f =>
      if (f.isFile) {
        try System.load(f.getAbsolutePath) catch { case _: Throwable => () }
      }
    }
    try System.loadLibrary("vJoyInterface") catch { case _: Throwable => () }
  }

  private def toAxis(x: Double): Int = {
    val c = math.max(-1.0, math.min(1.0, x))
    (((c + 1.0) / 2.0) * 0x7FFF + 1).toInt
  }

  def tryVJoy[F[_]](implicit F: Sync[F]): Option[VirtualGamepadPort[F]] = {
    try {
      preloadNative()
      val inst = new VJoy()
      val devId = 1
      val acquired =
        try inst.acquireVJD(devId)
        catch { case _: Throwable => false }
      if (!acquired) return None
      announce("[vpad] vJoy backend active")
      Some(new VirtualGamepadPort[F] {
        def send(cmd: ControlCommand): F[Unit] = F.delay {
          inst.setAxis(toAxis(cmd.yaw.value).toLong,    devId, 0x30)
          inst.setAxis(toAxis(1.0 - 2.0 * cmd.throttle.value).toLong, devId, 0x31)
          inst.setAxis(toAxis(cmd.roll.value).toLong,   devId, 0x32)
          inst.setAxis(toAxis(-cmd.pitch.value).toLong, devId, 0x33)
          // No updateVJD call needed with per-axis updates
        }
      })
    } catch {
      case _: Throwable => None
    }
  }

  def noop[F[_]: Sync]: VirtualGamepadPort[F] = new VirtualGamepadPort[F] {
    def send(cmd: ControlCommand): F[Unit] = Sync[F].unit
  }

  def select[F[_]](implicit F: Sync[F]): VirtualGamepadPort[F] =
    tryVJoy[F].getOrElse {
      // no print on fallback
      noop[F]
    }

  def trySelect[F[_]](implicit F: Sync[F]): Option[VirtualGamepadPort[F]] =
    sys.props.get("vpad.backend").orElse(sys.env.get("VPAD_BACKEND")).map(_.toLowerCase) match {
      case Some("noop") => None
      case _            => tryVJoy[F]
    }
}
