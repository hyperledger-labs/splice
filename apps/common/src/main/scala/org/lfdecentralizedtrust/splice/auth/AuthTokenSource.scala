// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.jwt.{AuthServiceJWTCodec, Jwt, JwtDecoder, StandardJWTPayload}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.auth.OAuthApi.TokenResponse
import org.lfdecentralizedtrust.splice.config.AuthTokenSourceConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object AuthToken {
  /* Creates a token that never expires */
  def apply(accessToken: String): AuthToken =
    AuthToken(accessToken, CantonTimestamp.MaxValue, user = guessLedgerApiUser(accessToken))
  def apply(tokenResponse: TokenResponse): AuthToken =
    AuthToken(
      accessToken = tokenResponse.access_token,
      expiresAt = CantonTimestamp.now().plusMillis(tokenResponse.expiresIn.toMillis),
      user = guessLedgerApiUser(tokenResponse.access_token),
    )

  /** Attempts to interpret the token the same way the participant server would,
    *  and returns the user the token is associated with, if the token is associated with exactly one user.
    */
  def guessLedgerApiUser(accessToken: String): Option[String] = {
    import spray.json.*
    for {
      decoded <- JwtDecoder.decode(Jwt(accessToken)).toOption
      json <- Try(decoded.payload.parseJson).toOption
      // Note: Splice only uses audience-based tokens (i.e., the standard JWT format).
      // AuthServiceJWTCodec.readPayload() guesses the token format, but only works if audience-based tokens
      // use the default ledger API audience prefix.
      payload <- Try(AuthServiceJWTCodec.readAudienceBasedToken(json)).toOption
    } yield {
      payload match {
        case standard: StandardJWTPayload => standard.userId
      }
    }
  }
}

/** @param accessToken The access token
  * @param expiresAt The time at which the token expires
  * @param user The user the token is associated with, if known.
  *             `None` means the token is either not associated with a user (e.g., canton admin tokens),
  *             or it is not known if the token is associated with a user (e.g., the token uses an unknown format).
  */
final case class AuthToken(accessToken: String, expiresAt: CantonTimestamp, user: Option[String])

sealed trait AuthTokenSource {
  def getToken(implicit tc: TraceContext): Future[Option[AuthToken]]
}

case class AuthTokenSourceNone() extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[AuthToken]] =
    Future.successful(None)
}

case class AuthTokenSourceStatic(
    token: String
) extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[AuthToken]] =
    Future.successful(Some(AuthToken(token)))
}

case class AuthTokenSourceSelfSigned(
    audience: String,
    user: String,
    secret: String,
    expiration: NonNegativeFiniteDuration,
) extends AuthTokenSource {
  override def getToken(implicit tc: TraceContext): Future[Option[AuthToken]] =
    Future.successful(
      Some(AuthToken(AuthUtil.testTokenSecret(audience, user, secret, expiration)))
    )
}

case class AuthTokenSourceOAuthClientCredentials(
    wellKnownConfigUrl: String,
    clientId: String,
    clientSecret: String,
    audience: String,
    scope: Option[String],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, ac: ActorSystem)
    extends AuthTokenSource
    with NamedLogging {
  private val oauth = new OAuthApi(loggerFactory)

  override def getToken(implicit tc: TraceContext): Future[Option[AuthToken]] = {
    for {
      wk <- oauth.getWellKnown(wellKnownConfigUrl)
      tokenResponse <- oauth.requestToken(
        wk.token_endpoint,
        clientId,
        clientSecret,
        audience,
        scope,
      )
    } yield Some(
      AuthToken(tokenResponse)
    )
  }
}

object AuthTokenSource {
  def fromConfig(
      config: AuthTokenSourceConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, ac: ActorSystem): AuthTokenSource = config match {
    case AuthTokenSourceConfig.None() =>
      new AuthTokenSourceNone()
    case AuthTokenSourceConfig.Static(token, _, _) =>
      new AuthTokenSourceStatic(token)
    case AuthTokenSourceConfig.SelfSigned(audience, user, secret, _, expiration) =>
      new AuthTokenSourceSelfSigned(audience, user, secret, expiration)
    case AuthTokenSourceConfig.ClientCredentials(
          wellKnownConfigUrl,
          clientId,
          clientSecret,
          audience,
          scope,
          _,
        ) =>
      new AuthTokenSourceOAuthClientCredentials(
        wellKnownConfigUrl = wellKnownConfigUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        loggerFactory = loggerFactory,
        audience = audience,
        scope = scope,
      )
  }
}
