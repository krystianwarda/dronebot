// Scala
package com.dronebot.core.flightcontrol.checks

import cats.effect.Sync
import redlaboratory.jvjoyinterface.VjdStat

final class VJoyRadioHealth[F[_]](vJoy: AnyRef)(implicit F: Sync[F]) extends RadioHealth[F] {

  private def tryInvoke(obj: AnyRef, name: String, args: Seq[AnyRef]): Option[AnyRef] = {
    val methods = obj.getClass.getMethods.filter(_.getName == name)
    methods.iterator.flatMap { m =>
      try {
        if (m.getParameterCount == args.length) {
          val res = m.invoke(obj, args: _*)
          Option(res.asInstanceOf[AnyRef])
        } else None
      } catch {
        case _: Throwable => None
      }
    }.toSeq.headOption
  }

  private def tryBoolean(obj: AnyRef, name: String, args: Seq[AnyRef]): Option[Boolean] =
    tryInvoke(obj, name, args).map {
      case b: java.lang.Boolean => b.booleanValue()
      case other => other != null // non-null result treat as success
    }

  override def isConnected: F[Boolean] =
    F.blocking {
      val id = Integer.valueOf(1)
      try {
        val enabled =
          tryBoolean(vJoy, "isVJDEnabled", Seq(id))
            .orElse(tryBoolean(vJoy, "isVjdEnabled", Seq(id)))
            .getOrElse(false)

        val statusOpt = tryInvoke(vJoy, "getVJDStatus", Seq(id))

        val statusOk = statusOpt match {
          case Some(s) => s == VjdStat.VJD_STAT_FREE
          case None    => false
        }

        enabled && statusOk
      } catch {
        case _: Throwable => false
      }
    }

  override def isResponsive: F[Boolean] =
    F.blocking {
      val id = Integer.valueOf(1)
      try {
        // attempt several plausible setAxis signatures by trying methods named "setAxis"
        // invoke with (0, id, 0) as a safe default
        val args = Seq(Integer.valueOf(0): AnyRef, id: AnyRef, Integer.valueOf(0): AnyRef)
        tryBoolean(vJoy, "setAxis", args).getOrElse {
          // if method exists but returns nothing, treat invocation success as responsive
          tryInvoke(vJoy, "setAxis", args).isDefined
        }
      } catch {
        case _: Throwable => false
      }
    }
}
