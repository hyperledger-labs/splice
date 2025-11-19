// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.NonNegativeDuration

sealed trait AuthTokenSourceConfig {
  // Token that will be used for all commands that need to bypass ledger API auth.
  // Due to the way Canton console is designed, this need to be a static token.
  def adminToken: Option[String]
}

object AuthTokenSourceConfig {
  final case class None() extends AuthTokenSourceConfig {
    override def adminToken: Option[String] = scala.None
  }

  /** Static, non-expiring token. Use for testing purposes only. */
  final case class Static(
      token: String,
      adminToken: Option[String],
  ) extends AuthTokenSourceConfig

  /** Settings for generating self-signed tokens. Use for testing purposes only. */
  final case class SelfSigned(
      audience: String,
      user: String,
      secret: String,
      adminToken: Option[String],
  ) extends AuthTokenSourceConfig

  /** Using OAuth client credentials flow to acquire tokens */
  final case class ClientCredentials(
      /** URL for the well-known OpenID configuration, see https://openid.net/specs/openid-connect-discovery-1_0.html */
      wellKnownConfigUrl: String,
      clientId: String,
      clientSecret: String,
      audience: String,
      scope: Option[String],
      requestTimeout: NonNegativeDuration = NonNegativeDuration.ofSeconds(30),
      adminToken: Option[String],
  ) extends AuthTokenSourceConfig

  def hideConfidential(config: AuthTokenSourceConfig): AuthTokenSourceConfig = {
    val hidden = "****"
    val hide = (t: Option[String]) => t.map(_ => hidden)
    config match {
      case None() => None()
      case Static(_, adminToken) => Static(hidden, hide(adminToken))
      case SelfSigned(audience, user, _, adminToken) =>
        SelfSigned(audience, user, hidden, hide(adminToken))
      case ClientCredentials(
            wellKnownConfigUrl,
            clientId,
            _,
            audience,
            scope,
            requestTimeout,
            adminToken,
          ) =>
        ClientCredentials(
          wellKnownConfigUrl,
          clientId,
          hidden,
          audience,
          scope,
          requestTimeout,
          hide(adminToken),
        )
    }
  }
}
