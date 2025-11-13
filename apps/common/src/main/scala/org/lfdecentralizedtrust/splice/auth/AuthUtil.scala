// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

// See also: com.daml.ledger.api.auth.Main from the Daml SDK contains utils for generating ledger API access tokens
object AuthUtil {

  val testAudience: String =
    sys.env.getOrElse(
      "OIDC_AUTHORITY_LEDGER_API_AUDIENCE",
      sys.env("SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_AUDIENCE"),
    )
  val testSecret: String = "test"
  val testSignatureAlgorithm: Algorithm = Algorithm.HMAC256(testSecret)

  /** We expect the audience field to have format `https://<domain>/<version>/<app-name>` */
  def audience(
      address: String,
      version: String,
      app: String,
  ) = s"$address/$version/$app"

  def testToken(
      audience: String,
      user: String,
      secret: String,
  ): String = {
    testTokenSecret(audience, user, secret)
  }

  def testTokenSecret(
      audience: String,
      user: String,
      secret: String,
  ): String = {
    JWT
      .create()
      .withSubject(user)
      .withAudience(audience)
      .sign(Algorithm.HMAC256(secret))
  }

  object LedgerApi {

    /** Uses scope-based tokens for the ledger API, see https://docs.daml.com/app-dev/authorization.html#scope-based-tokens.
      *
      * Notes:
      *  - We don't use audience-based tokens because they require you to include the participant id,
      *    which is randomly assigned at runtime
      *  - We don't use custom claims based tokens, because they require you to use party names instead of user names
      */
    def testToken(
        user: String,
        secret: String,
    ): String = {
      JWT
        .create()
        .withSubject(user)
        .withClaim("scope", "daml_ledger_api")
        .withAudience(testAudience)
        .sign(Algorithm.HMAC256(secret))
    }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  def main(args: Array[String]): Unit = {
    println(testToken(args(0), args(1), testSecret))
  }
}
