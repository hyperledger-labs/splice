package com.daml.network.auth

sealed trait AuthConfig {
  val enableAuth: Boolean
}

object AuthConfig {
  case class Hs256Unsafe(
      // TODO(i1012) -- remove this option when we require auth
      enableAuth: Boolean = true,
      secret: String,
  ) extends AuthConfig

  case class Rs256(
      // TODO(i1012) -- remove this option when we require auth
      enableAuth: Boolean = true,
      jwksUrl: String,
  ) extends AuthConfig
}
