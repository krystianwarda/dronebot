// Scala
package com.dronebot.adapters.simdroneinfo

import cats.effect.{Async, Resource}
import cats.syntax.all._
import fs2.Stream

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.Arrays

import com.dronebot.core.domain.flight._
final case class Vec3(x: Double, y: Double, z: Double)
final case class Quaternion(x: Double, y: Double, z: Double, w: Double)
final case class Inputs(roll: Double, pitch: Double, yaw: Double, throttle: Double)
final case class Battery(voltage: Double, current: Double)

final case class DroneTelemetry(
                                 timestampSec: Double,
                                 position: Vec3,
                                 attitude: Quaternion,
                                 velocity: Vec3,
                                 gyro: Vec3,
                                 inputs: Inputs,
                                 battery: Battery,
                                 motorRpms: Vector[Double],
                                 packetSize: Int
                               )

object DroneTelemetryCodec {
  // Base layout: 20 little-endian floats (80 bytes)
  // ts(1) + position(3) + attitude(4) + velocity(3) + gyro(3) + inputs(4) + battery(2)
  private val BaseFloatCount = 20
  private val BaseSize       = BaseFloatCount * 4

  def parse(bytes: Array[Byte]): Option[DroneTelemetry] = {
    if (bytes.length < BaseSize) return None

    val bb = ByteBuffer.wrap(bytes, 0, BaseSize).order(ByteOrder.LITTLE_ENDIAN)
    def f(): Double = bb.getFloat().toDouble

    val ts = f()

    // Read as X,Y,Z then swap Y<->Z so that Z becomes height
    val posX = f(); val posY = f(); val posZ = f()
    val position = Vec3(posX, posZ, posY)

    val attitude = Quaternion(f(), f(), f(), f())

    // Same swap for velocity: Z carries vertical speed
    val velX = f(); val velY = f(); val velZ = f()
    val velocity = Vec3(velX, velZ, velY)

    val gyro    = Vec3(f(), f(), f())
    val inputs  = Inputs(f(), f(), f(), f())
    val battery = Battery(f(), f())

    // Remaining: 1 byte motor_count, then 4*count floats of rpms (optional)
    val remaining = Arrays.copyOfRange(bytes, BaseSize, bytes.length)
    val motorRpms =
      if (remaining.nonEmpty) {
        val count = remaining(0) & 0xff
        val rpmBytes = Arrays.copyOfRange(remaining, 1, remaining.length)
        if (rpmBytes.length >= count * 4) {
          val bbR = ByteBuffer.wrap(rpmBytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
          Vector.fill(count)(bbR.getFloat().toDouble)
        } else Vector.empty[Double]
      } else Vector.empty[Double]

    Some(
      DroneTelemetry(
        timestampSec = ts,
        position = position,
        attitude = attitude,
        velocity = velocity,
        gyro = gyro,
        inputs = inputs,
        battery = battery,
        motorRpms = motorRpms,
        packetSize = bytes.length
      )
    )
  }
}

final class TelemetryUdpReceiver[F[_]](bindIp: String = "0.0.0.0", port: Int = 9001)(implicit F: Async[F]) {

  private def socketR: Resource[F, DatagramSocket] =
    Resource.make {
      F.blocking {
        val addr = InetAddress.getByName(bindIp)
        val s = new DatagramSocket(port, addr)
        s.setReuseAddress(true)
        s
      }
    } { s => F.blocking(s.close()).handleError(_ => ()) }

  def stream: Stream[F, DroneTelemetry] =
    Stream.resource(socketR).flatMap { sock =>
      val bufSize = 2048
      Stream.repeatEval {
        F.blocking {
          val buf = new Array[Byte](bufSize)
          val pkt = new DatagramPacket(buf, buf.length)
          sock.receive(pkt)
          Arrays.copyOf(buf, pkt.getLength)
        }.map(DroneTelemetryCodec.parse)
      }.unNone
    }
}
