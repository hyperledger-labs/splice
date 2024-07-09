// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.http.v0.definitions
import com.daml.network.validator.config.AppManagerConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPublicKey, RSAPrivateKey}
import java.util.{Base64, UUID}

class OAuth2Manager(
    config: AppManagerConfig,
    protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  // TODO(#6839) Consider persisting this so that a restart does not require a token refresh.
  private val keyId = UUID.randomUUID.toString

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def generateKey() = {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    val keyPair = keyGen.generateKeyPair()
    (keyPair.getPublic.asInstanceOf[RSAPublicKey], keyPair.getPrivate.asInstanceOf[RSAPrivateKey])
  }

  private val (publicKey, privateKey) = generateKey()

  this.getClass.getSimpleName

  // Map from authorization code to user id
  private val codes: collection.concurrent.Map[String, String] =
    collection.concurrent.TrieMap.empty

  val jwk: definitions.Jwk =
    definitions.Jwk(
      kid = keyId,
      kty = "RSA",
      use = "sig",
      alg = "RS256",
      // Somewhat annoyingly the Auth0 library only does JWKS decoding but not encoding
      // so we somewhat handroll it here.
      n = Base64.getUrlEncoder().encodeToString(publicKey.getModulus.toByteArray),
      e = Base64.getUrlEncoder().encodeToString(publicKey.getPublicExponent.toByteArray),
    )

  def generateAuthorizationCode(userId: String): String = {
    val authorizationCode = UUID.randomUUID.toString
    codes += authorizationCode -> userId
    authorizationCode
  }

  def getJwt(code: String): Option[String] =
    codes.remove(code).map { userId =>
      // TODO(#6839) Properly validate client_id, redirect_uri, ...
      JWT
        .create()
        .withSubject(userId)
        .withAudience(config.audience)
        // Needed so the JSON API parses the token properly
        .withClaim("scope", "daml_ledger_api")
        .withIssuer(config.issuerUrl.toString)
        .withKeyId(keyId)
        .sign(Algorithm.RSA256(publicKey, privateKey))
    }
}
