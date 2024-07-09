// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.identities

import better.files.File
import cats.syntax.either.*
import com.daml.network.http.v0.definitions as http
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.NodeIdentity
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.util.Base64
import scala.util.Try

final case class NodeIdentitiesDump(
    id: NodeIdentity,
    keys: Seq[NodeIdentitiesDump.NodeKey],
    authorizedStoreSnapshot: Option[ByteString],
    // TODO(#11594): Deprecated; to be removed in a future version.
    bootstrapTxs: Option[Seq[GenericSignedTopologyTransactionX]],
    version: Option[String],
) extends PrettyPrinting {
  def toHttp: http.NodeIdentitiesDump = {
    http.NodeIdentitiesDump(
      id.toProtoPrimitive,
      keys
        .map(key => http.NodeKey(Base64.getEncoder.encodeToString(key.keyPair.toArray), key.name))
        .toVector,
      authorizedStoreSnapshot.map(s => Base64.getEncoder.encodeToString(s.toByteArray)),
      bootstrapTxs.map(_.map(tx => Base64.getEncoder.encodeToString(tx.toByteArray)).toVector),
      version,
    )
  }
  def toJson: Json = {
    toHttp.asJson
  }

  import Pretty.*

  override def pretty: Pretty[NodeIdentitiesDump.this.type] =
    prettyNode(
      "NodeIdentitiesDump",
      param("id", _.id),
      param("numberOfKeys", _.keys.size),
      param("bootstrapTxs", _.bootstrapTxs),
      param("authorizedStoreSnapshotSize", _.authorizedStoreSnapshot.map(_.size)),
      param("version", _.version.map(_.singleQuoted)),
    )
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
        authorizedStoreSnapshot = response.authorizedStoreSnapshot.map(s =>
          ByteString.copyFrom(Base64.getDecoder.decode(s))
        ),
        bootstrapTxs = response.bootstrapTxs.map(
          _.toSeq.map(t =>
            SignedTopologyTransactionX
              .fromByteStringUnsafe(ByteString.copyFrom(Base64.getDecoder.decode(t)))
              .fold(err => throw new IllegalArgumentException(err.message), identity)
          )
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
