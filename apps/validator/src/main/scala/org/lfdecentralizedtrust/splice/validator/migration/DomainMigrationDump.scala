// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.migration

import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.migration.Dar
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.google.protobuf.ByteString
import io.circe.{Codec, Decoder, Encoder}

import java.util.Base64
import java.time.Instant

final case class DomainMigrationDump(
    domainId: DomainId,
    migrationId: Long,
    participant: NodeIdentitiesDump,
    acsSnapshot: ByteString,
    acsTimestamp: Instant,
    dars: Seq[Dar],
    createdAt: Instant,
) extends PrettyPrinting {
  override def pretty: Pretty[DomainMigrationDump.this.type] =
    Pretty.prettyNode(
      "DomainMigrationDump",
      param("domainId", _.domainId),
      param("migrationId", _.migrationId),
      param("participant", _.participant),
      param("acsSnapshotSize", _.acsSnapshot.size),
      param("acsTimestamp", _.acsTimestamp),
      param("darsSize", _.dars.size),
      param("createdAt", _.createdAt),
    )

  def toHttp: http.DomainMigrationDump = http.DomainMigrationDump(
    participant = participant.toHttp,
    acsSnapshot = Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    acsTimestamp = acsTimestamp.toString,
    dars = dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.hash.toHexString, content)
    }.toVector,
    migrationId = migrationId,
    domainId = domainId.toProtoPrimitive,
    createdAt = createdAt.toString,
  )
}

object DomainMigrationDump {
  implicit val domainMigrationCodec: Codec[DomainMigrationDump] =
    Codec.from(
      // We try legacy first so that if both fail the return error comes from the non-legacy decoder.
      Decoder[http.LegacyDomainMigrationDump]
        .map(fromLegacy(_))
        .handleErrorWith(_ => Decoder[http.DomainMigrationDump]) emap fromHttp,
      Encoder[http.DomainMigrationDump] contramap (_.toHttp),
    )

  private val base64Decoder = Base64.getDecoder()

  private def fromLegacy(legacy: http.LegacyDomainMigrationDump): http.DomainMigrationDump =
    http.DomainMigrationDump(
      legacy.participant,
      legacy.acsSnapshot,
      legacy.acsTimestamp,
      legacy.dars,
      legacy.migrationId,
      legacy.domainId,
      legacy.createdAt,
    )

  def fromHttp(response: http.DomainMigrationDump) = for {
    participant <- NodeIdentitiesDump
      .fromHttp(ParticipantId.tryFromProtoPrimitive, response.participant)
      .leftMap(_ => "Failed to parse Participant Node Identities")
    domainId <- DomainId fromString response.domainId
    migrationId = response.migrationId
    acsSnapshot = {
      val decoded = base64Decoder.decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = response.dars.map { dar =>
      val decoded = base64Decoder.decode(dar.content)
      Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
    }
    createdAt = Instant.parse(response.createdAt)
  } yield DomainMigrationDump(
    domainId = domainId,
    migrationId = migrationId,
    participant = participant,
    acsSnapshot = acsSnapshot,
    acsTimestamp = Instant.parse(response.acsTimestamp),
    dars = dars,
    createdAt = createdAt,
  )
}
