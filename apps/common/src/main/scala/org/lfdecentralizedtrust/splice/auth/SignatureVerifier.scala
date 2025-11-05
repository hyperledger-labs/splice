// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.digitalasset.canton.config.NonNegativeDuration
import org.lfdecentralizedtrust.splice.http.HttpClient

import java.net.URL
import java.util.concurrent.TimeUnit
import scala.util.Try

trait SignatureVerifier {
  protected val expectedAudience: String;

  def verify(token: String): Either[String, DecodedJWT] = for {
    algorithm <- getAlgorithm(token)
    verifiedToken <- Try(
      JWT
        .require(algorithm)
        .build()
        .verify(token)
    ).toEither.left.map(_.toString())
    validToken <- validateAudience(verifiedToken)
  } yield validToken

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
  protected def validateAudience(jwt: DecodedJWT): Either[String, DecodedJWT] = {
    if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
      Right(jwt)
    } else {
      Left(
        s"Expected audience $expectedAudience does not match actual audience ${jwt.getAudience()}"
      )
    }
  }
}

class RSAVerifier(audience: String, jwksUrl: URL, timeoutsConfig: RSAVerifier.TimeoutsConfig)
    extends SignatureVerifier {
  override val expectedAudience: String = audience;
  private val httpProxySettings = HttpClient.ProxySettings.readFromEnvVars()

  val provider: JwkProvider = httpProxySettings
    .map { proxySettings =>
      new JwkProviderBuilder(jwksUrl).proxied(
        new java.net.Proxy(
          java.net.Proxy.Type.HTTP,
          proxySettings.address,
        )
      )
    }
    .getOrElse(new JwkProviderBuilder(jwksUrl))
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .timeouts(
      // You'd need 2^31 milliseconds for this to overflow, which is about 25 days.
      // Surely nobody needs timeouts that long.
      timeoutsConfig.connectTimeout.duration.toMillis.toInt,
      timeoutsConfig.readTimeout.duration.toMillis.toInt,
    )
    .build()

  private def algorithm = Algorithm.RSA256(new JwksRSAKeyProvider(provider))
  override def validateAlgorithm(algorithm: String) = algorithm match {
    case "RS256" => Right(this.algorithm)
    case _ => Left("Invalid token algorithm for rs-256 auth mode")
  }
}
object RSAVerifier {
  case class TimeoutsConfig(connectTimeout: NonNegativeDuration, readTimeout: NonNegativeDuration)
}

class HMACVerifier(audience: String, secret: String) extends SignatureVerifier {
  override val expectedAudience: String = audience

  private def algorithm = Algorithm.HMAC256(secret)
  override def validateAlgorithm(algorithm: String) = algorithm match {
    case "HS256" => Right(this.algorithm)
    case _ => Left("Invalid token algorithm for hs-256-unsafe auth mode")
  }
}
