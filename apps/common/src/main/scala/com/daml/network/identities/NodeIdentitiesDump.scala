package com.daml.network.identities

import better.files.File
import com.daml.network.http.v0.definitions as http
import com.digitalasset.canton.topology.NodeIdentity
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax.*
import cats.syntax.either.*

import java.nio.file.Path
import java.util.Base64
import scala.util.Try

final case class NodeIdentitiesDump(
    id: NodeIdentity,
    keys: Seq[NodeIdentitiesDump.NodeKey],
    bootstrapTxs: Seq[GenericSignedTopologyTransactionX],
    version: Option[String],
) {
  def toHttp: http.NodeIdentitiesDump = {
    http.NodeIdentitiesDump(
      id.toProtoPrimitive,
      keys
        .map(key => http.NodeKey(Base64.getEncoder.encodeToString(key.keyPair.toArray), key.name))
        .toVector,
      bootstrapTxs.map(tx => Base64.getEncoder.encodeToString(tx.toByteArray)).toVector,
      version,
    )
  }
  def toJson: Json = {
    toHttp.asJson
  }
}
object NodeIdentitiesDump {
  def fromHttp(
      id: String => NodeIdentity,
      response: http.NodeIdentitiesDump,
  ): Either[String, NodeIdentitiesDump] = {
    Try(
      NodeIdentitiesDump(
        id = id(response.id),
        keys =
          response.keys.toSeq.map(k => NodeKey(Base64.getDecoder.decode(k.keyPair).toSeq, k.name)),
        bootstrapTxs = response.bootstrapTxs.toSeq.map(t =>
          SignedTopologyTransactionX
            .fromByteStringUnsafe(ByteString.copyFrom(Base64.getDecoder.decode(t)))
            .fold(err => throw new IllegalArgumentException(err.message), identity)
        ),
        version = response.version,
      )
    ).toEither.leftMap(_.getMessage())
  }

  def fromJsonString(id: String => NodeIdentity, json: String): Either[String, NodeIdentitiesDump] =
    io.circe.parser.parse(json).leftMap(_.getMessage()).flatMap(fromJson(id, _))

  def fromJsonFile(file: Path, id: String => NodeIdentity): Either[String, NodeIdentitiesDump] = {
    val jsonString = Try(File(file).contentAsString).toEither.leftMap(_.getMessage())
    jsonString.flatMap(fromJsonString(id, _))
  }

  def fromJson(id: String => NodeIdentity, json: Json): Either[String, NodeIdentitiesDump] = {
    json
      .as[http.NodeIdentitiesDump]
      .leftMap(_.getMessage())
      .flatMap(fromHttp(id, _))
  }

  final case class NodeKey(
      keyPair: Seq[Byte],
      name: Option[String],
  )
}
