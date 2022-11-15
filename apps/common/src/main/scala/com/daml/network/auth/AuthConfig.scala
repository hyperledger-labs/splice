package com.daml.network.auth

import java.net.URL

sealed trait AuthConfig {}

object AuthConfig {
  case class Hs256Unsafe(
      secret: String
  ) extends AuthConfig

  case class Rs256(
      jwksUrl: URL
  ) extends AuthConfig
}
