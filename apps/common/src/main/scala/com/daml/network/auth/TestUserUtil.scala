package com.daml.network.auth

sealed trait LoginParameters {}

object LoginParameters {
  case class SelfSigned(
      secret: String
  ) extends LoginParameters

  case class Auth0(
      uiUrl: String,
      userId: String,
      userEmail: String,
      password: String,
  ) extends LoginParameters
}

case class UserCredential(username: String, token: Option[String]);
