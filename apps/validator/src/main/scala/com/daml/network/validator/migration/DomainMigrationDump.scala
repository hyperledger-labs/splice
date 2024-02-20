package com.daml.network.validator.migration

import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.migration.Dar
import com.daml.network.util
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.google.protobuf.ByteString
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

import java.util.Base64
import scala.annotation.nowarn

case class DomainMigrationDump(
    domainId: DomainId,
    migrationId: Long,
    participant: NodeIdentitiesDump,
    acsSnapshot: ByteString,
    dars: Seq[Dar],
)

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
}
