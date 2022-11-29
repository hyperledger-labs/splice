package com.daml.network.auth

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.{ExecutionContext, Future}

final case class ClientCredentialRequest(
    client_id: String,
    client_secret: String,
    audience: String,
    grant_type: String = "client_credentials",
)
final case class TokenResponse(access_token: String)

trait ClientCredentialGrantJson extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val clientCredentialRequestFormat = jsonFormat4(ClientCredentialRequest)
  implicit val tokenResponseFormat = jsonFormat1(TokenResponse)
}

class ClientCredentialGrant()(implicit executionContext: ExecutionContext)
    extends ClientCredentialGrantJson {
  def requestToken(
      tokenUrl: String,
      clientId: String,
      clientSecret: String,
      audience: String,
  ): Future[String] = {
    implicit val system = ActorSystem("SingleRequest")

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
