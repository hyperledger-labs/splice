// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.util

import com.auth0.jwt.{JWT, JWTVerifier}
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.daml.network.util.{Codec, CodecCompanion}
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import io.circe.parser.decode as circeDecode
import io.circe.syntax.*

import java.security.interfaces.ECPrivateKey
import scala.util.Try

private object JsonProtocol {
  import io.circe.{Decoder, Encoder, Json}
  implicit val SvOnboardingTokenEncoder: Encoder[SvOnboardingToken] = t =>
    Json.obj(
      "name" -> Json.fromString(t.candidateName),
      "key" -> Json.fromString(t.candidateKey),
      "party" -> Json.fromString(t.candidateParty.toProtoPrimitive),
      "participantId" -> Json.fromString(t.candidateParticipantId.toProtoPrimitive),
      "dso" -> Json.fromString(t.dsoParty.toProtoPrimitive),
    )

  implicit val SvOnboardingTokenDecoder: Decoder[SvOnboardingToken] = { c =>
    implicit val partyDecoder: Decoder[PartyId] = codecDecoder(Codec.Party)
    implicit val participantDecoder: Decoder[ParticipantId] = codecDecoder(Codec.Participant)
    for {
      name <- c.downField("name").as[String]
      key <- c.downField("key").as[String]
      party <- c.downField("party").as[PartyId]
      participantId <- c.downField("participantId").as[ParticipantId]
      dso <- c.downField("dso").as[PartyId]
    } yield new SvOnboardingToken(name, key, party, participantId, dso)
  }

  private[this] def codecDecoder[Dec](codec: CodecCompanion[Dec])(implicit
      json: Decoder[codec.Enc]
  ): Decoder[Dec] =
    json emap codec.instance.decode
}
import JsonProtocol.*

case class SvOnboardingToken(
    candidateName: String,
    candidateKey: String,
    candidateParty: PartyId,
    candidateParticipantId: ParticipantId,
    dsoParty: PartyId,
) {
  def signAndEncode(privateKey: ECPrivateKey): Either[String, String] = for {
    publicKey <- SvUtil.parsePublicKey(candidateKey)
    token <- Try(
      JWT
        .create()
        .withClaim(SvOnboardingToken.Claim, this.asJson.noSpaces)
        .sign(Algorithm.ECDSA256(publicKey, privateKey))
    ).toEither.left.map(_.toString())
  } yield token
}
object SvOnboardingToken {
  val Claim = "https://lfdecentralizedtrust.splice/sv"

  def verifyAndDecode(rawToken: String): Either[String, SvOnboardingToken] = for {
    verifier <- getVerifier(rawToken) // extracts the public key
    verifiedToken <- Try(verifier.verify(rawToken)).toEither.left.map(_.toString())
    payload <- extractPayload(verifiedToken)
  } yield payload

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private def getVerifier(token: String): Either[String, JWTVerifier] = for {
    unverifiedToken <- decodeNoVerify(token)
    payload <- extractPayload(unverifiedToken)
    publicKeyBase64 = payload.candidateKey
    publicKey <- SvUtil.parsePublicKey(publicKeyBase64)
  } yield JWT
    .require(Algorithm.ECDSA256(publicKey, null))
    .build

  private def extractPayload(decodedToken: DecodedJWT): Either[String, SvOnboardingToken] = {
    val payload = decodedToken.getClaim(Claim)
    circeDecode[SvOnboardingToken](payload.asString).left.map(_.toString())
  }

  private def decodeNoVerify(token: String): Either[String, DecodedJWT] =
    Try(JWT.decode(token)).toEither.left.map(_.toString);
}
