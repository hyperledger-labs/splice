// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.migration.{Dar, ParticipantUsersData}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.google.protobuf.ByteString

import java.time.Instant
import java.util.Base64

// TODO(DACH-NY/canton-network-node#11100) Split domain data snapshots for validators and SVs to avoid
// the optional mess.
final case class DomainDataSnapshot(
    genesisState: Option[ByteString],
    acsSnapshot: ByteString,
    acsTimestamp: Instant,
    dars: Seq[Dar],
    // true if we exported for a proper migration, false for DR.
    synchronizerWasPaused: Boolean,
) extends PrettyPrinting {
  def toHttp: http.DomainDataSnapshot = http.DomainDataSnapshot(
    genesisState.map(s => Base64.getEncoder.encodeToString(s.toByteArray)),
    Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    acsTimestamp.toString,
    dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.mainPackageId, content)
    }.toVector,
    synchronizerWasPaused = Some(synchronizerWasPaused),
  )

  override def pretty: Pretty[DomainDataSnapshot.this.type] =
    Pretty.prettyNode(
      "DomainDataSnapshot",
      paramIfDefined("genesisStateSize", _.genesisState.map(_.size)),
      param("acsSnapshotSize", _.acsSnapshot.size),
      param("acsTimestamp", _.acsTimestamp),
      param("darsSize", _.dars.size),
      param("synchronizerWasPaused", _.synchronizerWasPaused),
    )
}

object DomainDataSnapshot {
  final case class Response(
      migrationId: Long,
      dataSnapshot: DomainDataSnapshot,
      participantUsers: ParticipantUsersData,
  ) {
    def createdAt: dataSnapshot.acsTimestamp.type = dataSnapshot.acsTimestamp
  }

  object Response {
    def fromHttp(src: http.GetDomainDataSnapshotResponse): Either[String, Response] = for {
      dataSnapshot <- DomainDataSnapshot fromHttp src.dataSnapshot
      participantUsers = ParticipantUsersData.fromHttp(src.participantUsers)
    } yield Response(src.migrationId, dataSnapshot, participantUsers)
  }

  private val base64Decoder = Base64.getDecoder()

  def fromHttp(
      src: http.DomainDataSnapshot
  ): Either[String, DomainDataSnapshot] = {
    val genesisState = src.genesisState.map(s => ByteString.copyFrom(base64Decoder.decode(s)))
    val acsSnapshot = ByteString.copyFrom(base64Decoder.decode(src.acsSnapshot))
    val dars =
      src.dars.map { dar =>
        val decoded = base64Decoder.decode(dar.content)
        Dar(dar.hash, ByteString.copyFrom(decoded))
      }
    val acsTimestamp = Instant.parse(src.acsTimestamp)
    Right(
      DomainDataSnapshot(
        genesisState,
        acsSnapshot,
        acsTimestamp,
        dars,
        src.synchronizerWasPaused.getOrElse(false),
      )
    )
  }
}
