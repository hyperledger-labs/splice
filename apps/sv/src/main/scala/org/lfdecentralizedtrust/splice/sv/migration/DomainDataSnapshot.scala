// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.migration.{Dar, ParticipantUsersData}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.google.protobuf.ByteString

import java.io.*
import java.time.Instant
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using

// TODO(DACH-NY/canton-network-node#11100) Split domain data snapshots for validators and SVs to avoid
// the optional mess.
final case class DomainDataSnapshot(
    genesisState: Option[Seq[ByteString]],
    acsSnapshot: Seq[ByteString],
    acsTimestamp: Instant,
    dars: Seq[Dar],
    // true if we exported for a proper migration, false for DR.
    synchronizerWasPaused: Boolean,
) extends PrettyPrinting {
  // if output directory is specified we use the new format, otherwise the old one.
  // Only the DR endpoint should use the old one.
  def toHttp(outputDirectory: Option[String]): http.DomainDataSnapshot = {
    def encodeField(name: String, content: Seq[ByteString]): String = {
      outputDirectory match {
        case None =>
          Base64.getEncoder.encodeToString(ByteString.copyFrom(content.asJava).toByteArray)
        case Some(dir) =>
          val file = s"$dir/${acsTimestamp}-$name"
          Using.resource(
            new DataOutputStream(
              new BufferedOutputStream(
                new FileOutputStream(file)
              )
            )
          ) { dos =>
            DomainDataSnapshot.writeChunks(dos, content)
          }
          file
      }
    }

    http.DomainDataSnapshot(
      genesisState.map(s => encodeField("genesis-state", s)),
      encodeField("acs-snapshot", acsSnapshot),
      acsTimestamp.toString,
      dars.map { dar =>
        val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
        http.Dar(dar.mainPackageId, content)
      }.toVector,
      synchronizerWasPaused = Some(synchronizerWasPaused),
      separatePayloadFiles = outputDirectory.isDefined,
    )
  }

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
    val dars =
      src.dars.map { dar =>
        val decoded = base64Decoder.decode(dar.content)
        Dar(dar.hash, ByteString.copyFrom(decoded))
      }
    val acsTimestamp = Instant.parse(src.acsTimestamp)
    Right(
      DomainDataSnapshot(
        src.genesisState.map(readBytes(src.separatePayloadFiles, _)),
        readBytes(src.separatePayloadFiles, src.acsSnapshot),
        acsTimestamp,
        dars,
        src.synchronizerWasPaused.getOrElse(false),
      )
    )
  }

  def readBytes(separateFiles: Boolean, content: String): Seq[ByteString] = {
    if (separateFiles) {
      Using.resource(
        new DataInputStream(
          new BufferedInputStream(
            new FileInputStream(content)
          )
        )
      )(readAllChunks)
    } else {
      Seq(ByteString.copyFrom(base64Decoder.decode(content)))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  private def readAllChunks(
      dis: DataInputStream
  ): Seq[ByteString] = {
    val acc = ListBuffer.empty[ByteString]
    var eof = false
    while (!eof) {
      try {
        val length = dis.readInt()
        val chunk = new Array[Byte](length)
        dis.readFully(chunk)
        acc.addOne(ByteString.copyFrom(chunk))
      } catch {
        case _: EOFException =>
          eof = true
      }
    }
    acc.toSeq
  }

  private def writeChunks(dos: DataOutputStream, chunks: Seq[ByteString]): Unit = {
    chunks.foreach { chunk =>
      dos.writeInt(chunk.size)
      dos.write(chunk.toByteArray)
    }
  }
}
