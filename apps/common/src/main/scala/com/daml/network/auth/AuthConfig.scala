package com.daml.network.auth

sealed trait AuthConfig {}

object AuthConfig {
  case class Hs256Unsafe(
      secret: String
  ) extends AuthConfig

  case class Rs256(
      jwksUrl: String
  ) extends AuthConfig
}
