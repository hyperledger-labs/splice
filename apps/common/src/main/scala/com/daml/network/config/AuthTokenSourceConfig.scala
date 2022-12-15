package com.daml.network.config

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
      adminToken: Option[String],
  ) extends AuthTokenSourceConfig
}
