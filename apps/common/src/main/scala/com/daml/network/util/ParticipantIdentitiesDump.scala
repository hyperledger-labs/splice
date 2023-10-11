package com.daml.network.util

import better.files.File
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.daml.network.http.v0.definitions as http
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.util.Base64
import scala.util.Try

final case class ParticipantIdentitiesDump(
    id: ParticipantId,
    keys: Seq[ParticipantIdentitiesDump.ParticipantKey],
    bootstrapTxs: Seq[GenericSignedTopologyTransactionX],
    version: Option[String],
) {
  def toHttp: http.ParticipantIdentitiesDump = {
    http.ParticipantIdentitiesDump(
      id.toProtoPrimitive,
      keys
        .map(key => http.ParticipantKey(Base64.getEncoder().encodeToString(key.keyPair), key.name))
        .toVector,
      bootstrapTxs.map(tx => Base64.getEncoder().encodeToString(tx.toByteArray)).toVector,
      version,
    )
  }
  def toJson: Json = {
    toHttp.asJson
  }
}
object ParticipantIdentitiesDump {
  def fromHttp(
      response: http.ParticipantIdentitiesDump
  ): Either[String, ParticipantIdentitiesDump] = {
    Try(
      ParticipantIdentitiesDump(
        id = ParticipantId.tryFromProtoPrimitive(response.id),
        keys =
          response.keys.toSeq.map(k => ParticipantKey(Base64.getDecoder.decode(k.keyPair), k.name)),
        bootstrapTxs = response.bootstrapTxs.toSeq.map(t =>
          SignedTopologyTransactionX
            .fromByteArray(Base64.getDecoder.decode(t))
            .fold(err => throw new IllegalArgumentException(err.message), identity)
        ),
        version = response.version,
      )
    ).toEither.left.map(_.getMessage())
  }
  def fromJsonString(json: String): Either[String, ParticipantIdentitiesDump] =
    io.circe.parser
      .decode[http.ParticipantIdentitiesDump](json)
      .left
      .map(_.getMessage())
      .flatMap(fromHttp)

  def fromJsonFile(file: Path): Either[String, ParticipantIdentitiesDump] = {
    val jsonString = Try(File(file).contentAsString).toEither.left.map(_.getMessage())
    jsonString.flatMap(fromJsonString)
  }

  final case class ParticipantKey(
      keyPair: Array[Byte],
      name: Option[String],
  )
}
