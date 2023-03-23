package com.daml.network.environment

import cats.syntax.functor.*
import cats.syntax.either.*
import cats.syntax.traverse.*

import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.topology.UniqueIdentifier
import java.time.Duration
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.health.admin.{v0 as protoV0}
import com.daml.network.http.v0.{definitions as jsonV0}
import com.digitalasset.canton.serialization.ProtoConverter.DurationConverter
import com.digitalasset.canton.ProtoDeserializationError.InvariantViolation

import com.google.protobuf.ByteString

// A fork of SimpleStatus from Canton for Coin apps, without topologyQueues member
case class CNNodeStatus(
    uid: UniqueIdentifier,
    uptime: Duration,
    ports: Map[String, Port],
    active: Boolean,
) extends NodeStatus.Status {
  private[environment] def portsString(ports: Map[String, Port]): String =
    multiline(ports.map { case (portDescription, port) =>
      s"$portDescription: ${port.unwrap}"
    }.toSeq)
  private[environment] def multiline(elements: Seq[String]): String =
    if (elements.isEmpty) "None" else elements.map(el => s"\n\t$el").mkString

  override def pretty: Pretty[CNNodeStatus] =
    prettyOfString(_ =>
      Seq(
        s"Node uid: ${uid.toProtoPrimitive}",
        show"Uptime: $uptime",
        s"Ports: ${portsString(ports)}",
        s"Active: $active",
      ).mkString(System.lineSeparator())
    )

  // unused, but definition required by NodeStatus.Status trait
  def toProtoV0: protoV0.NodeStatus.Status =
    protoV0.NodeStatus.Status(
      uid.toProtoPrimitive,
      Some(DurationConverter.toProtoPrimitive(uptime)),
      ports.fmap(_.unwrap),
      ByteString.EMPTY,
      active,
      None,
    )

  def toJsonV0: jsonV0.Status =
    jsonV0.Status(
      uid.toProtoPrimitive,
      uptime.toString,
      ports.map[String, Int]({ case (key, port) => (key, port.unwrap) }),
      None,
      active,
    )
}

object CNNodeStatus {
  def fromJsonV0(json: jsonV0.Status): Either[String, CNNodeStatus] = {
    for {
      uid <- UniqueIdentifier
        .fromProtoPrimitive(json.id, "Status.id")
        .leftMap(_ => "Failed to deserialize UID")
      ports <- json.ports.toList
        .traverse { case (json, i) =>
          Port
            .create(i)
            .leftMap(InvariantViolation.toProtoDeserializationError)
            .map(p => (json, p))
        }
        .map(_.toMap)
        .leftMap(_ => "Failed to deserialize ports")
    } yield CNNodeStatus(
      uid,
      Duration.parse(json.uptime),
      ports,
      json.active,
    )
  }

  def fromNodeStatus(status: NodeStatus.Status): CNNodeStatus =
    CNNodeStatus(status.uid, status.uptime, status.ports, status.active)
}
