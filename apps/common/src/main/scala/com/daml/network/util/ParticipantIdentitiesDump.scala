package com.daml.network.util

import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.daml.network.http.v0.definitions as http
import io.circe.Json
import io.circe.syntax.*

import java.util.Base64
import scala.util.Try

final case class ParticipantIdentitiesDump(
    id: ParticipantId,
    keys: Seq[ParticipantIdentitiesDump.ParticipantKey],
    bootstrapTxs: Seq[Array[Byte]],
    users: Seq[ParticipantIdentitiesDump.ParticipantUser],
) {
  def toHttp: http.ParticipantIdentitiesDump = {
    http.ParticipantIdentitiesDump(
      id.toProtoPrimitive,
      keys
        .map(key => http.ParticipantKey(Base64.getEncoder().encodeToString(key.keyPair), key.name))
        .toVector,
      bootstrapTxs.map(tx => Base64.getEncoder().encodeToString(tx)).toVector,
      users
        .map(user => http.ParticipantUser(user.id, user.primaryParty.map(_.toProtoPrimitive)))
        .toVector,
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
        bootstrapTxs = response.bootstrapTxs.toSeq.map(t => Base64.getDecoder.decode(t)),
        users = response.users.toSeq.map(user =>
          ParticipantUser(user.id, user.primaryParty.map(PartyId.tryFromProtoPrimitive(_)))
        ),
      )
    ).toEither.left.map(_.getMessage())
  }
  def fromJsonString(json: String): Either[String, ParticipantIdentitiesDump] =
    io.circe.parser
      .decode[http.ParticipantIdentitiesDump](json)
      .left
      .map(_.getMessage())
      .flatMap(fromHttp)

  final case class ParticipantKey(
      keyPair: Array[Byte],
      name: Option[String],
  )
  final case class ParticipantUser(
      id: String,
      primaryParty: Option[PartyId],
  )
}
