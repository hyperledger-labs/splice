package com.daml.network.environment

import cats.syntax.either.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import com.daml.network.http.v0.{definitions as jsonV0}
import com.digitalasset.canton.ProtoDeserializationError.InvariantViolation
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.health.ComponentStatus
import com.digitalasset.canton.health.admin.{v0 as protoV0}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.serialization.ProtoConverter.DurationConverter
import com.digitalasset.canton.topology.UniqueIdentifier
import com.google.protobuf.ByteString

import java.time.Duration

// A fork of SimpleStatus from Canton for Coin apps, without topologyQueues member
case class CNNodeStatus(
    uid: UniqueIdentifier,
    uptime: Duration,
    ports: Map[String, Port],
    active: Boolean,
) extends NodeStatus.Status {
  // TODO(#3859) Set this to something useful.
  override def components: Seq[ComponentStatus] = Seq.empty

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
      components.map(_.toProtoV0),
    )

  def toHttp: jsonV0.Status =
    jsonV0.Status(
      uid.toProtoPrimitive,
      uptime.toString,
      ports.map[String, Int]({ case (key, port) => (key, port.unwrap) }),
      None,
      active,
    )
}

object CNNodeStatus {
  def fromStatus[S <: NodeStatus.Status](status: S): CNNodeStatus =
    CNNodeStatus(
      status.uid,
      status.uptime,
      status.ports,
      status.active,
    )
  def toHttpNodeStatus[S <: NodeStatus.Status](status: NodeStatus[S]): jsonV0.NodeStatus =
    status match {
      case NodeStatus.Success(status) =>
        jsonV0.NodeStatus(success = Some(CNNodeStatus.fromStatus(status).toHttp))
      case NodeStatus.NotInitialized(active) =>
        jsonV0.NodeStatus(
          notInitialized = Some(jsonV0.NotInitialized(active))
        )
      case NodeStatus.Failure(_) =>
        jsonV0.NodeStatus(None, None)
    }

  def fromHttpNodeStatus[S <: NodeStatus.Status](
      deserialize: jsonV0.Status => Either[String, S]
  )(status: jsonV0.NodeStatus): Either[String, NodeStatus[S]] =
    status match {
      case jsonV0.NodeStatus(None, Some(success)) => {
        deserialize(success).map(NodeStatus.Success(_))
      }
      case jsonV0.NodeStatus(Some(notInitialized), None) => {
        Right(NodeStatus.NotInitialized(notInitialized.active))
      }
      case _ => Left("Unsuccessful status response")
    }

  def fromHttp(json: jsonV0.Status): Either[String, CNNodeStatus] = {
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
