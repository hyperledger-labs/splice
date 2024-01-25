package com.daml.network.sv.util

import com.auth0.jwt.{JWT, JWTVerifier}
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.daml.network.util.Codec
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import spray.json.*

import java.security.interfaces.ECPrivateKey
import scala.util.Try

private object JsonProtocol extends DefaultJsonProtocol {
  implicit object SvOnboardingTokenJsonFormat extends RootJsonFormat[SvOnboardingToken] {
    def write(t: SvOnboardingToken) = JsObject(
      "name" -> JsString(t.candidateName),
      "key" -> JsString(t.candidateKey),
      "party" -> JsString(t.candidateParty.toProtoPrimitive),
      "participantId" -> JsString(t.candidateParticipantId.toProtoPrimitive),
      "svc" -> JsString(t.svcParty.toProtoPrimitive),
    )
    def read(value: JsValue) = {
      value.asJsObject.getFields("name", "key", "party", "participantId", "svc") match {
        case Seq(
              JsString(name),
              JsString(key),
              JsString(partyS),
              JsString(participantId),
              JsString(svcS),
            ) =>
          (for {
            party <- Codec.decode(Codec.Party)(partyS)
            svc <- Codec.decode(Codec.Party)(svcS)
            participantId <- Codec.decode(Codec.Participant)(participantId)
          } yield new SvOnboardingToken(name, key, party, participantId, svc))
            .getOrElse(throw new DeserializationException("Could not parse party IDs"))
        case _ => throw new DeserializationException("Wrong fields in JSON object")
      }
    }
  }
}
import JsonProtocol.*

case class SvOnboardingToken(
    candidateName: String,
    candidateKey: String,
    candidateParty: PartyId,
    candidateParticipantId: ParticipantId,
    svcParty: PartyId,
) {
  def signAndEncode(privateKey: ECPrivateKey): Either[String, String] = for {
    publicKey <- SvUtil.parsePublicKey(candidateKey)
    token <- Try(
      JWT
        .create()
        .withClaim(SvOnboardingToken.Claim, this.toJson.toString())
        .sign(Algorithm.ECDSA256(publicKey, privateKey))
    ).toEither.left.map(_.toString())
  } yield token
}
object SvOnboardingToken {

  val Claim = "https://canton.network.global/sv"

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
    Try(payload.asString.parseJson.convertTo[SvOnboardingToken]).toEither.left.map(_.toString())
  }

  private def decodeNoVerify(token: String): Either[String, DecodedJWT] =
    Try(JWT.decode(token)).toEither.left.map(_.toString);
}
