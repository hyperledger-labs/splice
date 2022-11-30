// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.daml.network.config.AuthTokenSourceConfig
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.Future

trait AuthTokenSource extends AutoCloseable {
  def getToken: Future[Option[String]]
}

class AuthTokenSourceNone() extends AuthTokenSource {
  override def getToken: Future[Option[String]] = Future.successful(None)
  override def close(): Unit = {}
}

class AuthTokenSourceStatic(
    token: String
) extends AuthTokenSource {
  override def getToken: Future[Option[String]] = Future.successful(Some(token))
  override def close(): Unit = {}
}

// TODO(#674): Implement this
class AuthTokenSourceOAuthClientCredentials(
    wellKnownConfigUrl: String,
    clientId: String,
    clientSecret: String,
    override protected val loggerFactory: NamedLoggerFactory,
    val timeouts: ProcessingTimeout,
) extends AuthTokenSource
    with NamedLogging {
  override def getToken: Future[Option[String]] = ???
  override def close(): Unit = ???
}

object AuthTokenSource {
  def fromConfig(
      config: AuthTokenSourceConfig,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  ): AuthTokenSource = config match {
    case AuthTokenSourceConfig.None() =>
      new AuthTokenSourceNone()
    case AuthTokenSourceConfig.Static(token, _) =>
      new AuthTokenSourceStatic(token)
    case AuthTokenSourceConfig.ClientCredentials(wellKnownConfigUrl, clientId, clientSecret, _) =>
      new AuthTokenSourceOAuthClientCredentials(
        wellKnownConfigUrl = wellKnownConfigUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        loggerFactory = loggerFactory,
        timeouts = timeouts,
      )
  }
}
