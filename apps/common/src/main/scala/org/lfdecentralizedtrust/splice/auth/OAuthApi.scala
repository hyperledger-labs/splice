// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.{FormData, HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

object OAuthApi {
  final case class ClientCredentialRequest(
      client_id: String,
      client_secret: String,
      audience: String,
      grant_type: String = "client_credentials",
  ) {
    def toFormData = FormData(
      "client_id" -> client_id,
      "client_secret" -> client_secret,
      "audience" -> audience,
      "grant_type" -> grant_type,
    )
  }

  /** expires_in is in seconds */
  final case class TokenResponse(access_token: String, expires_in: Long) {
    def expiresIn: FiniteDuration = FiniteDuration(expires_in, TimeUnit.SECONDS)
  }

  // We want to be compatible with a wide range of IAMs so we
  // only include the required fields based on the standard
  // https://openid.net/specs/openid-connect-discovery-1_0.html
  final case class WellKnownResponse(
      issuer: String,
      authorization_endpoint: String,
      token_endpoint: String,
      jwks_uri: String,
  )
}

// we use spray rather than circe here for its interaction with pekko http
trait OAuthApiJson extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val wellKnownResponseFormat: RootJsonFormat[OAuthApi.WellKnownResponse] = jsonFormat4(
    OAuthApi.WellKnownResponse.apply
  )
  implicit val tokenResponseFormat: RootJsonFormat[OAuthApi.TokenResponse] = jsonFormat2(
    OAuthApi.TokenResponse.apply
  )
}

class OAuthApi(
    override protected val loggerFactory: NamedLoggerFactory
)(implicit actorSystem: ActorSystem)
    extends OAuthApiJson
    with NamedLogging {
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  import OAuthApi.*

  private def decodeAndLog[T](res: HttpResponse, description: String)(implicit
      um: Unmarshaller[HttpResponse, T],
      tc: TraceContext,
  ): Future[T] = {
    logger.debug(s"$description response status: ${res.status}")
    Unmarshal(res)
      .to[T]
      .andThen { case Failure(e) =>
        Unmarshal(res)
          .to[String]
          .foreach(b => logger.warn(s"$description - failed to unmarshal: $b", e))
      }
  }

  def getWellKnown(
      url: String
  )(implicit tc: TraceContext): Future[WellKnownResponse] = {
    logger.debug(s"Loading OIDC Well-Known Configuration from $url")

    for {
      res <- Http().singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = url,
        )
      )
      body <- decodeAndLog[WellKnownResponse](res, "OIDC Well-Known configuration")
    } yield {
      logger.debug(s"Well-Known configuration is $body")
      body
    }
  }

  def requestToken(
      tokenUrl: String,
      clientId: String,
      clientSecret: String,
      audience: String,
  )(implicit tc: TraceContext): Future[TokenResponse] = {
    logger.debug(s"Using OAuth client credentials flow with clientId='$clientId' at $tokenUrl")

    val payload = ClientCredentialRequest(clientId, clientSecret, audience)

    val responseFuture: Future[HttpResponse] = Http().singleRequest(
      HttpRequest(
        method = HttpMethods.POST,
        uri = tokenUrl,
        entity = payload.toFormData.toEntity,
      )
    )

    for {
      res <- responseFuture
      tokenResponse <- decodeAndLog[TokenResponse](res, "OAuth token")
    } yield {
      tokenResponse
    }
  }
}
