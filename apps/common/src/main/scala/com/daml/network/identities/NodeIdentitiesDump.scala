package com.daml.network.identities

import better.files.File
import com.daml.network.http.v0.definitions as http
import com.digitalasset.canton.topology.NodeIdentity
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import io.circe.Json
import io.circe.syntax.*

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
        .map(key => http.NodeKey(Base64.getEncoder.encodeToString(key.keyPair), key.name))
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
        keys = response.keys.toSeq.map(k => NodeKey(Base64.getDecoder.decode(k.keyPair), k.name)),
        bootstrapTxs = response.bootstrapTxs.toSeq.map(t =>
          SignedTopologyTransactionX
            .fromByteArrayUnsafe(Base64.getDecoder.decode(t))
            .fold(err => throw new IllegalArgumentException(err.message), identity)
        ),
        version = response.version,
      )
    ).toEither.left.map(_.getMessage())
  }
  def fromJsonString(id: String => NodeIdentity, json: String): Either[String, NodeIdentitiesDump] =
    io.circe.parser
      .decode[http.NodeIdentitiesDump](json)
      .left
      .map(_.getMessage())
      .flatMap(fromHttp(id, _))

  def fromJsonFile(file: Path, id: String => NodeIdentity): Either[String, NodeIdentitiesDump] = {
    val jsonString = Try(File(file).contentAsString).toEither.left.map(_.getMessage())
    jsonString.flatMap(fromJsonString(id, _))
  }

  final case class NodeKey(
      keyPair: Array[Byte],
      name: Option[String],
  )
}
