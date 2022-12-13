package com.daml.network.auth

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import spray.json.{DefaultJsonProtocol, *}

import scala.concurrent.{ExecutionContext, Future}

object OAuthApi {
  final case class ClientCredentialRequest(
      client_id: String,
      client_secret: String,
      audience: String,
      grant_type: String = "client_credentials",
  )

  final case class TokenResponse(access_token: String)

  final case class WellKnownResponse(
      issuer: String,
      authorization_endpoint: String,
      token_endpoint: String,
      device_authorization_endpoint: String,
      userinfo_endpoint: String,
      mfa_challenge_endpoint: String,
      jwks_uri: String,
      registration_endpoint: String,
      revocation_endpoint: String,
  )
}

trait OAuthApiJson extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val clientCredentialRequestFormat = jsonFormat4(OAuthApi.ClientCredentialRequest)
  implicit val wellKnownResponseFormat = jsonFormat9(OAuthApi.WellKnownResponse)
  implicit val tokenResponseFormat = jsonFormat1(OAuthApi.TokenResponse)
}

class OAuthApi(
    override protected val loggerFactory: NamedLoggerFactory
)(implicit actorSystem: ActorSystem)
    extends OAuthApiJson
    with NamedLogging {
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  import OAuthApi.*

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
      body <- Unmarshal(res).to[WellKnownResponse]
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
  )(implicit tc: TraceContext): Future[String] = {
    logger.debug(s"Using OAuth client credentials flow with clientId='$clientId' at $tokenUrl")

    val payload = ClientCredentialRequest(clientId, clientSecret, audience).toJson.toString

    val responseFuture: Future[HttpResponse] = Http().singleRequest(
      HttpRequest(
        method = HttpMethods.POST,
        uri = tokenUrl,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          payload,
        ),
      )
    )

    for {
      res <- responseFuture
      body <- Unmarshal(res).to[TokenResponse]
    } yield {
      body.access_token
    }
  }
}
