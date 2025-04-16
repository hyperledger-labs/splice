// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.migration

import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.migration.{Dar, ParticipantUsersData}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{SynchronizerId, ParticipantId}
import com.google.protobuf.ByteString
import io.circe.{Codec, Decoder, Encoder}

import java.util.Base64
import java.time.Instant

final case class DomainMigrationDump(
    domainId: SynchronizerId,
    migrationId: Long,
    participant: NodeIdentitiesDump,
    participantUsers: Option[ParticipantUsersData],
    acsSnapshot: ByteString,
    acsTimestamp: Instant,
    dars: Seq[Dar],
    createdAt: Instant,
) extends PrettyPrinting {
  override def pretty: Pretty[DomainMigrationDump.this.type] =
    Pretty.prettyNode(
      "DomainMigrationDump",
      param("synchronizerId", _.domainId),
      param("migrationId", _.migrationId),
      param("participant", _.participant),
      param("participantUsers", _.participantUsers),
      param("acsSnapshotSize", _.acsSnapshot.size),
      param("acsTimestamp", _.acsTimestamp),
      param("darsSize", _.dars.size),
      param("createdAt", _.createdAt),
    )

  def toHttp: http.DomainMigrationDump = http.DomainMigrationDump(
    participant = participant.toHttp,
    participantUsers = participantUsers.map(_.toHttp),
    acsSnapshot = Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    acsTimestamp = acsTimestamp.toString,
    dars = dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.mainPackageId, content)
    }.toVector,
    migrationId = migrationId,
    domainId = domainId.toProtoPrimitive,
    createdAt = createdAt.toString,
  )
}

object DomainMigrationDump {
  implicit val domainMigrationCodec: Codec[DomainMigrationDump] =
    Codec.from(
      Decoder[http.DomainMigrationDump] emap fromHttp,
      Encoder[http.DomainMigrationDump] contramap (_.toHttp),
    )

  private val base64Decoder = Base64.getDecoder()

  def fromHttp(response: http.DomainMigrationDump) = for {
    participant <- NodeIdentitiesDump
      .fromHttp(ParticipantId.tryFromProtoPrimitive, response.participant)
      .leftMap(_ => "Failed to parse Participant Node Identities")
    participantUsers = response.participantUsers.map(ParticipantUsersData.fromHttp)
    domainId <- SynchronizerId fromString response.domainId
    migrationId = response.migrationId
    acsSnapshot = {
      val decoded = base64Decoder.decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = response.dars.map { dar =>
      val decoded = base64Decoder.decode(dar.content)
      Dar(dar.hash, ByteString.copyFrom(decoded))
    }
    createdAt = Instant.parse(response.createdAt)
  } yield DomainMigrationDump(
    domainId = domainId,
    migrationId = migrationId,
    participant = participant,
    participantUsers = participantUsers,
    acsSnapshot = acsSnapshot,
    acsTimestamp = Instant.parse(response.acsTimestamp),
    dars = dars,
    createdAt = createdAt,
  )
}
