package com.daml.network.auth

import com.auth0.jwt.interfaces.DecodedJWT

import scala.jdk.CollectionConverters._

case class CnClaims(
    daml_user: Option[String]
)

object JwtClaims {
  val CNClaimKey = "https://canton-network"

  def getCnClaims(token: DecodedJWT): Option[CnClaims] = {
    val claim = token.getClaim(CNClaimKey)

    Option(claim.asMap.asScala).flatMap {
      _.get("daml_user").map {
        _ match {
          case user: String => CnClaims(Some(user))
          case _ => CnClaims(None)
        }
      }
    }
  }

  /** Support two ways of specifying Daml user IDs in JWT tokens:
    *
    * 1: Set the standardized `subject` claim
    * 2: Set the `daml_user` claim in custom Canton Network claim object.
    *    This will take precedence over `subject` if both claims are set
    *
    * @param token The decoded token claims
    * @return Either the Daml user ID as a String, or None if neither claim is set
    */
  def getDamlUser(token: DecodedJWT): Option[String] = Option(
    getCnClaims(token).flatMap(_.daml_user).getOrElse(token.getSubject)
  )
}
