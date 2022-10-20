// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

// See also: com.daml.ledger.api.auth.Main from the Daml SDK contains utils for generating ledger API access tokens
object AuthUtil {

  val testSecret: String = "test"
  val testSignatureAlgorithm: Algorithm = Algorithm.HMAC256(testSecret)

  /** We expect the audience field to have format `https://<participant-id>.network.canton.global/<app-name>` */
  def audience(
      address: String,
      app: String,
  ) = s"$address/$app"

  def testToken(
      audience: String,
      user: String,
  ): String = {
    JWT
      .create()
      .withSubject(user)
      .withAudience(audience)
      .sign(testSignatureAlgorithm)
  }

  def main(args: Array[String]): Unit = {
    println(testToken(args(0), args(1)))
  }
}
