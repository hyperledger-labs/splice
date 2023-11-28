package com.daml.network.validator.util

import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpRequest,
  HttpResponse,
  StatusCodes,
  Uri,
}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.Materializer
import cats.syntax.either.*
import com.daml.network.environment.BaseAppConnection
import io.circe.parser.decode

import scala.concurrent.{ExecutionContext, Future}

private[validator] object HttpUtil {
  def getHttpJson[T](uri: Uri)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      decoder: io.circe.Decoder[T],
  ): Future[T] =
    for {
      response <- httpClient(HttpRequest(uri = uri))
      decoded <- response.status match {
        case StatusCodes.OK if (response.entity.contentType == ContentTypes.`application/json`) =>
          Unmarshal(response.entity).to[String].map { json =>
            decode[T](json).valueOr(err =>
              throw new IllegalArgumentException(s"Failed to decode manifest: $err")
            )
          }
        case _ => Future.failed(new BaseAppConnection.UnexpectedHttpResponse(response))
      }
    } yield decoded
}
