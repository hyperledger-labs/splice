package com.daml.network.auth

import java.net.URL

sealed trait AuthConfig {
  val audience: String
}

object AuthConfig {
  case class Hs256Unsafe(
      audience: String,
      secret: String,
  ) extends AuthConfig

  case class Rs256(
      audience: String,
      jwksUrl: URL,
  ) extends AuthConfig
}
