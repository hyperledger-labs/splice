package com.daml.network.auth

import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT

import java.util.concurrent.TimeUnit
import scala.util.Try

trait SignatureVerifier {
  def verify(token: String): Either[String, DecodedJWT] = for {
    algorithm <- getAlgorithm(token)
    verifiedToken <- Try(
      JWT
        .require(algorithm)
        .build()
        .verify(token)
    ).toEither.left.map(_.toString)
  } yield verifiedToken

  private def decodeNoVerify(token: String): Either[String, DecodedJWT] =
    Try(JWT.decode(token)).toEither.left.map(_.toString);

  protected def getAlgorithm(token: String): Either[String, Algorithm] =
    for {
      algorithmId <- decodeNoVerify(token).map(_.getAlgorithm)
      validatedAlgorithm <- validateAlgorithm(algorithmId)
    } yield {
      validatedAlgorithm
    }

  protected def validateAlgorithm(algorithm: String): Either[String, Algorithm]
}

class RSAVerifier(jwksUrl: String) extends SignatureVerifier {
  private val provider: JwkProvider = new JwkProviderBuilder(jwksUrl)
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()

  private def algorithm = Algorithm.RSA256(new JwksRSAKeyProvider(provider))
  override def validateAlgorithm(algorithm: String) = algorithm match {
    case "RS256" => Right(this.algorithm)
    case _ => Left("Invalid token algorithm for rs-256 auth mode")
  }
}

class HMACVerifier(secret: String) extends SignatureVerifier {
  private def algorithm = Algorithm.HMAC256(secret)
  override def validateAlgorithm(algorithm: String) = algorithm match {
    case "HS256" => Right(this.algorithm)
    case _ => Left("Invalid token algorithm for hs-256-unsafe auth mode")
  }
}
