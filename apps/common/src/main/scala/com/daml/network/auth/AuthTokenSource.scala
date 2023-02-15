// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import akka.actor.ActorSystem
import com.daml.network.config.AuthTokenSourceConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait AuthTokenSource {
  def getToken(implicit tc: TraceContext): Future[Option[String]]
}

class AuthTokenSourceNone() extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[String]] = Future.successful(None)
}

class AuthTokenSourceStatic(
    token: String
) extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[String]] =
    Future.successful(Some(token))
}

class AuthTokenSourceSelfSigned(
    audience: String,
    user: String,
    secret: String,
) extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[String]] =
    Future.successful(Some(AuthUtil.testTokenSecret(audience, user, secret)))
}

class AuthTokenSourceOAuthClientCredentials(
    wellKnownConfigUrl: String,
    clientId: String,
    clientSecret: String,
    audience: String,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, ac: ActorSystem)
    extends AuthTokenSource
    with NamedLogging {
  val oauth = new OAuthApi(loggerFactory)

  override def getToken(implicit tc: TraceContext): Future[Option[String]] =
    for {
      wk <- oauth.getWellKnown(wellKnownConfigUrl)
      token <- oauth.requestToken(wk.token_endpoint, clientId, clientSecret, audience)
    } yield Some(token)
}

object AuthTokenSource {
  def fromConfig(
      config: AuthTokenSourceConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, ac: ActorSystem): AuthTokenSource = config match {
    case AuthTokenSourceConfig.None() =>
      new AuthTokenSourceNone()
    case AuthTokenSourceConfig.Static(token, _) =>
      new AuthTokenSourceStatic(token)
    case AuthTokenSourceConfig.SelfSigned(audience, user, secret, _) =>
      new AuthTokenSourceSelfSigned(audience, user, secret)
    case AuthTokenSourceConfig.ClientCredentials(
          wellKnownConfigUrl,
          clientId,
          clientSecret,
          audience,
          _,
        ) =>
      new AuthTokenSourceOAuthClientCredentials(
        wellKnownConfigUrl = wellKnownConfigUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        loggerFactory = loggerFactory,
        audience = audience,
      )
  }
}
