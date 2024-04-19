package com.daml.network.validator.migration

import cats.syntax.either.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.migration.Dar
import com.daml.network.util
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.google.protobuf.ByteString
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

import java.util.Base64
import java.time.Instant
import scala.annotation.nowarn

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

  import util.Codec.domainIdValue

  implicit def cnCodecToCirceCodec[T](implicit codec: util.Codec[T, String]): Codec[T] = Codec.from(
    Decoder.decodeString.emap(codec.decode),
    Encoder.encodeString.contramap(codec.encode),
  )

  implicit val identityEncoder: Encoder[NodeIdentitiesDump] = _.toJson
  implicit val identityDecoder: Decoder[NodeIdentitiesDump] = Decoder.decodeJson.emap(
    NodeIdentitiesDump.fromJson(
      ParticipantId.tryFromProtoPrimitive,
      _,
    )
  )

  private val darEncoder: Encoder.AsObject[Dar] =
    Encoder.forProduct2[Dar, String, String]("hash", "content")(dar =>
      (dar.hash.toHexString, Base64.getEncoder.encodeToString(dar.content.toByteArray))
    )
  private val darDecoder: Decoder[Dar] =
    Decoder.forProduct2[Dar, String, String]("hash", "content")((hash, content) =>
      Dar(Hash.tryFromHexString(hash), ByteString.copyFrom(Base64.getDecoder.decode(content)))
    )

  implicit val darCodec: Codec[Dar] = Codec.from(darDecoder, darEncoder)
  implicit val byteStringCodec: Codec[ByteString] = Codec.from(
    Decoder.decodeString.map(content => ByteString.copyFrom(Base64.getDecoder.decode(content))),
    Encoder.encodeString.contramap[ByteString](byteString =>
      Base64.getEncoder.encodeToString(byteString.toByteArray)
    ),
  )

  @nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
  implicit val domainMigrationCodec: Codec[DomainMigrationDump] =
    deriveCodec[DomainMigrationDump]

  private val base64Decoder = Base64.getDecoder()

  def fromHttp(response: http.DomainMigrationDump) = for {
    participant <- NodeIdentitiesDump
      .fromHttp(ParticipantId.tryFromProtoPrimitive, response.participant)
      .leftMap(_ => "Failed to parse Participant Node Identities")
    domainId = DomainId.tryFromString(response.domainId)
    migrationId = response.migrationId
    acsSnapshot = {
      val decoded = base64Decoder.decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = response.dars.map { dar =>
      val decoded = base64Decoder.decode(dar.content)
      Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
    }
    createdAt = Instant.parse(response.createdAt),
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
