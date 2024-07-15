// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.auth0.jwt.interfaces.DecodedJWT

case class SpliceClaims(
    daml_user: Option[String]
)

object JwtClaims {
  private[this] val ClaimKey = "https://canton-network"

  private[this] def getClaims(token: DecodedJWT): Option[SpliceClaims] = Option(
    token.getClaim(ClaimKey).as(classOf[SpliceClaims])
  )

  /** Support two ways of specifying Daml user IDs in JWT tokens:
    *
    * 1: Set the standardized `subject` claim
    * 2: Set the `daml_user` claim in custom Canton Network claim object.
    *    This will take precedence over `subject` if both claims are set
    *
    * @param token The decoded token claims
    * @return Either the Daml user ID as a String, or None if neither claim is set
    */
  def getLedgerApiUser(token: DecodedJWT): Option[String] = Option(
    getClaims(token).flatMap(_.daml_user).getOrElse(token.getSubject)
  )
}
