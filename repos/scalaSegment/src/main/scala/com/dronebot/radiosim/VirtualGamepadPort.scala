package com.dronebot.radiosim

import cats.effect.Sync
import com.dronebot.domain.ControlCommand
import java.io.File

trait VirtualGamepadPort[F[_]] {
  def send(cmd: ControlCommand): F[Unit]
}

object VirtualGamepadPort {
  private[this] var announced = false
  private def announce(msg: String): Unit = if (!announced) { println(msg); announced = true }
  private def debugEnabled: Boolean = sys.props.get("vpad.debugNative").exists(_.equalsIgnoreCase("true"))

  private def dllRoot  = new File("C:\\tools\\DLLs")
  private def dll64Dir = new File(dllRoot, "64")
  private def vjoyDll  = new File(dll64Dir, "vJoyInterface.dll")
  private def jniDll   = new File(dllRoot, "libJvJoyInterfaceNative.dll")

  private def preloadNative(): Unit = {
    println(s"[vpad] java.library.path = ${System.getProperty("java.library.path")}")
    Seq(vjoyDll, jniDll).foreach { f =>
      if (f.isFile) {
        try {
          System.load(f.getAbsolutePath)
          if (debugEnabled) println(s"[vpad] Loaded absolute: ${f.getAbsolutePath}")
        } catch {
          case e: UnsatisfiedLinkError =>
            println(s"[vpad] Failed absolute load: ${f.getAbsolutePath} (${e.getMessage})")
          case _: Throwable => ()
        }
      } else println(s"[vpad] Missing file: ${f.getAbsolutePath}")
    }
    try {
      System.loadLibrary("vJoyInterface")

      if (debugEnabled) println("[vpad] loadLibrary(\"64/vJoyInterface\") succeeded")
    } catch {
      case e: UnsatisfiedLinkError =>
        println(s"[vpad] loadLibrary(\"64/vJoyInterface\") failed: ${e.getMessage}")
        println("[vpad] Ensure VM option -Djava.library.path=C:\\tools\\DLLs is set when launching.")
    }
  }

  private def loadVJoyClass(): Class[_] = {
    preloadNative()
    val candidates = List("com.github.rlj1202.jvjoyinterface.VJoy", "redlaboratory.jvjoyinterface.VJoy")
    var nativeErr: Throwable = null
    var classMissing = true
    for (name <- candidates) {
      try {
        val c = Class.forName(name)
        return c
      } catch {
        case e: UnsatisfiedLinkError =>
          // Class exists, but static init failed (native not loaded)
          nativeErr = e
          classMissing = false
          println(s"[vpad] Native init failed for $name: ${e.getMessage}")
        case e: ExceptionInInitializerError =>
          nativeErr = Option(e.getCause).getOrElse(e)
          classMissing = false
          println(s"[vpad] Static initializer failed for $name: ${nativeErr.getMessage}")
        case e: ClassNotFoundException =>
          if (debugEnabled) println(s"[vpad] Class not on classpath: $name")
        case e: Throwable =>
          nativeErr = e
          classMissing = false
          println(s"[vpad] Unexpected init error for $name: ${e.getClass.getSimpleName}: ${e.getMessage}")
      }
    }
    if (!classMissing && nativeErr != null) throw nativeErr
    throw new ClassNotFoundException(s"VJoy implementation not found: ${candidates.mkString(", ")}")
  }

  def noop[F[_]: Sync]: VirtualGamepadPort[F] = new VirtualGamepadPort[F] {
    def send(cmd: ControlCommand): F[Unit] = Sync[F].unit
  }

  def tryVJoy[F[_]](implicit F: Sync[F]): Option[VirtualGamepadPort[F]] = {
    def toAxis(x: Double): Int = {
      val c = math.max(-1.0, math.min(1.0, x))
      (((c + 1.0) / 2.0) * 0x7FFF + 1).toInt
    }
    try {
      val cls  = loadVJoyClass()
      println(s"[vpad] vJoy class loaded: ${cls.getName}")
      val inst = cls.getConstructor().newInstance()
      println("[vpad] vJoy instance created")

      val devId    = Int.box(1)
      val acquireM = cls.getMethod("acquireVJD", classOf[Int])
      val setAxisM = cls.getMethod("setAxis", classOf[Long], classOf[Int], classOf[Int])

      val updateM  = try Some(cls.getMethod("updateVJD", classOf[Int])) catch { case _: Throwable => None }

      val acquired = try acquireM.invoke(inst, devId).asInstanceOf[Boolean] catch {
        case e: Throwable =>
          println(s"[vpad] acquireVJD threw: ${e.getClass.getSimpleName}: ${e.getMessage}")
          return None
      }
      if (!acquired) {
        println("[vpad] Device 1 not acquired. Use vJoyConf to enable Device 1.")
        return None
      }

      announce("[vpad] vJoy backend active")
      Some(new VirtualGamepadPort[F] {
        def send(cmd: ControlCommand): F[Unit] = F.delay {
          setAxisM.invoke(inst, Long.box(toAxis(cmd.yaw.value)),     devId, Int.box(0x30))
          setAxisM.invoke(inst, Long.box(toAxis(1.0 - 2.0 * cmd.throttle.value)), devId, Int.box(0x31))
          setAxisM.invoke(inst, Long.box(toAxis(cmd.roll.value)),    devId, Int.box(0x32))
          setAxisM.invoke(inst, Long.box(toAxis(-cmd.pitch.value)),  devId, Int.box(0x33))

          updateM.foreach(_.invoke(inst, devId))
        }
      })
    } catch {
      case e: UnsatisfiedLinkError =>
        println(s"[vpad] Native link error: ${e.getMessage}")
        println("[vpad] Required files:")
        println("[vpad]   C:\\tools\\DLLs\\64\\vJoyInterface.dll")
        println("[vpad]   C:\\tools\\DLLs\\libJvJoyInterfaceNative.dll")
        None
      case e: ClassNotFoundException =>
        println(s"[vpad] VJoy class not found: ${e.getMessage}")
        None
      case e: Throwable =>
        println(s"[vpad] Unexpected vJoy error: ${e.getClass.getSimpleName}: ${e.getMessage}")
        None
    }
  }

  def select[F[_]](implicit F: Sync[F]): VirtualGamepadPort[F] =
    tryVJoy[F].getOrElse {
      announce("[vpad] No virtual gamepad backend; using noop")
      noop[F]
    }

  def trySelect[F[_]](implicit F: Sync[F]): Option[VirtualGamepadPort[F]] =
    sys.props.get("vpad.backend").orElse(sys.env.get("VPAD_BACKEND")).map(_.toLowerCase) match {
      case Some("noop") => None
      case _            => tryVJoy[F]
    }
}