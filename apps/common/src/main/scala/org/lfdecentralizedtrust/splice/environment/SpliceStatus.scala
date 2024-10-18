// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.syntax.either.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.http.v0.definitions as jsonV0
import com.digitalasset.canton.ProtoDeserializationError.InvariantViolation
import com.digitalasset.canton.admin.api.client.data.{ComponentStatus, NodeStatus}
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.UniqueIdentifier

import java.time.Duration

// A fork of SimpleStatus from Canton for Amulet apps, without topologyQueues member
case class SpliceStatus(
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

  override def pretty: Pretty[SpliceStatus] =
    prettyOfString(_ =>
      Seq(
        s"Node uid: ${uid.toProtoPrimitive}",
        show"Uptime: $uptime",
        s"Ports: ${portsString(ports)}",
        s"Active: $active",
      ).mkString(System.lineSeparator())
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

object SpliceStatus {
  def fromStatus[S <: NodeStatus.Status](status: S): SpliceStatus =
    SpliceStatus(
      status.uid,
      status.uptime,
      status.ports,
      status.active,
    )
  def toHttpNodeStatus[S <: NodeStatus.Status](status: NodeStatus[S]): jsonV0.NodeStatus =
    status match {
      case NodeStatus.Success(status) =>
        jsonV0.SuccessStatusResponse(SpliceStatus.fromStatus(status).toHttp)
      case NodeStatus.NotInitialized(active, _) =>
        jsonV0.NotInitializedStatusResponse(jsonV0.NotInitialized(active))
      case NodeStatus.Failure(msg) =>
        jsonV0.FailureStatusResponse(jsonV0.ErrorResponse(msg))
    }

  def fromHttpNodeStatus[S <: NodeStatus.Status](
      deserialize: jsonV0.Status => Either[String, S]
  )(status: jsonV0.NodeStatus): Either[String, NodeStatus[S]] =
    status match {
      case jsonV0.NodeStatus.members.SuccessStatusResponse(jsonV0.SuccessStatusResponse(success)) =>
        deserialize(success).map(NodeStatus.Success(_))
      case jsonV0.NodeStatus.members
            .NotInitializedStatusResponse(jsonV0.NotInitializedStatusResponse(notInitialized)) =>
        Right(NodeStatus.NotInitialized(notInitialized.active, None))
      case jsonV0.NodeStatus.members.FailureStatusResponse(jsonV0.FailureStatusResponse(failure)) =>
        Left(failure.error)
    }

  def fromHttp(json: jsonV0.Status): Either[String, SpliceStatus] = {
    for {
      uid <- UniqueIdentifier
        .fromProtoPrimitive(json.id, "Status.id")
        .leftMap(_ => "Failed to deserialize UID")
      ports <- json.ports.toList
        .traverse { case (json, port) =>
          Port
            .create(port)
            .leftMap(InvariantViolation.toProtoDeserializationError(port.toString, _))
            .map(p => (json, p))
        }
        .map(_.toMap)
        .leftMap(_ => "Failed to deserialize ports")
    } yield SpliceStatus(
      uid,
      Duration.parse(json.uptime),
      ports,
      json.active,
    )
  }

  def fromNodeStatus(
      status: com.digitalasset.canton.health.admin.data.NodeStatus.Status
  ): SpliceStatus =
    SpliceStatus(status.uid, status.uptime, status.ports, status.active)
}
