package com.daml.network.auth

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import spray.json.{DefaultJsonProtocol, *}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

object OAuthApi {
  final case class ClientCredentialRequest(
      client_id: String,
      client_secret: String,
      audience: String,
      grant_type: String = "client_credentials",
  )

  final case class TokenResponse(access_token: String)

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

trait OAuthApiJson extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val clientCredentialRequestFormat = jsonFormat4(OAuthApi.ClientCredentialRequest)
  implicit val wellKnownResponseFormat = jsonFormat4(OAuthApi.WellKnownResponse)
  implicit val tokenResponseFormat = jsonFormat1(OAuthApi.TokenResponse)
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
      body <- decodeAndLog[TokenResponse](res, "OAuth token")
    } yield {
      body.access_token
    }
  }
}
