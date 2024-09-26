// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.identities

import better.files.File
import cats.syntax.either.*
import com.daml.network.http.v0.definitions as http
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.NodeIdentity
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.util.Base64
import scala.util.Try

final case class NodeIdentitiesDump(
    id: NodeIdentity,
    keys: Seq[NodeIdentitiesDump.NodeKey],
    authorizedStoreSnapshot: ByteString,
    version: Option[String],
) extends PrettyPrinting {
  def toHttp: http.NodeIdentitiesDump = {
    val httpKeys = keys.map {
      case NodeIdentitiesDump.NodeKey.KeyPair(keyPair, name) =>
        http.NodeKey.members.KeyPair(
          http.KeyPair(Base64.getEncoder.encodeToString(keyPair.toArray), name)
        ): http.NodeKey
      case NodeIdentitiesDump.NodeKey.KmsKeyId(
            NodeIdentitiesDump.NodeKey.KeyType.Signing,
            keyId,
            name,
          ) =>
        http.NodeKey.members.KmsKeyId(
          http.KmsKeyId(http.KmsKeyId.Type.Signing, keyId, name)
        ): http.NodeKey
      case NodeIdentitiesDump.NodeKey.KmsKeyId(
            NodeIdentitiesDump.NodeKey.KeyType.Encryption,
            keyId,
            name,
          ) =>
        http.NodeKey.members.KmsKeyId(
          http.KmsKeyId(http.KmsKeyId.Type.Encryption, keyId, name)
        ): http.NodeKey
    }.toVector
    http.NodeIdentitiesDump(
      id.toProtoPrimitive,
      httpKeys,
      Base64.getEncoder.encodeToString(authorizedStoreSnapshot.toByteArray),
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
      param("authorizedStoreSnapshotSize", _.authorizedStoreSnapshot.size),
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
        keys = response.keys.toSeq.map {
          case http.NodeKey.members.KmsKeyId(
                http.KmsKeyId(http.KmsKeyId.Type.members.Signing, keyId, name)
              ) =>
            NodeKey.KmsKeyId(NodeKey.KeyType.Signing, keyId, name)
          case http.NodeKey.members.KmsKeyId(
                http.KmsKeyId(http.KmsKeyId.Type.members.Encryption, keyId, name)
              ) =>
            NodeKey.KmsKeyId(NodeKey.KeyType.Encryption, keyId, name)
          case http.NodeKey.members.KeyPair(http.KeyPair(keyPair, name)) =>
            NodeKey.KeyPair(Base64.getDecoder.decode(keyPair).toSeq, name)
        },
        authorizedStoreSnapshot =
          ByteString.copyFrom(Base64.getDecoder.decode(response.authorizedStoreSnapshot)),
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

  sealed trait NodeKey {
    def name: Option[String]
  }

  object NodeKey {
    sealed trait KeyType
    object KeyType {
      case object Signing extends KeyType
      case object Encryption extends KeyType
    }

    final case class KeyPair(
        keyPair: Seq[Byte],
        name: Option[String],
    ) extends NodeKey

    final case class KmsKeyId(
        keyType: KeyType,
        keyId: String,
        name: Option[String],
    ) extends NodeKey
  }
}
