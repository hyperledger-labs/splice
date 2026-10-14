// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import cats.data.EitherT
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.`Content-Range`
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.http.v0.Implicits.*
import org.lfdecentralizedtrust.splice.http.v0.PekkoHttpImplicits.*

import scala.concurrent.{ExecutionContext, Future}

/** Guardrail doesn't seem to nicely autogenerate streaming clients so we handwrite it here.
  */
object SvStreamClient {
  def apply(host: String = "https://example.com")(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ): SvStreamClient =
    new SvStreamClient(host = host)(httpClient = httpClient, ec = ec, mat = mat)
  def httpClient(
      httpClient: HttpRequest => Future[HttpResponse],
      host: String = "https://example.com",
  )(implicit ec: ExecutionContext, mat: Materializer): SvStreamClient =
    new SvStreamClient(host = host)(httpClient = httpClient, ec = ec, mat = mat)

  sealed abstract class OnboardSvDownloadResponse
  object OnboardSvDownloadResponse {
    case class OK(entity: ResponseEntity, reprDigest: Option[String])
        extends OnboardSvDownloadResponse
    case class PartialContent(
        entity: ResponseEntity,
        contentRange: Option[ContentRange],
        reprDigest: Option[String],
    ) extends OnboardSvDownloadResponse
    case object RangeNotSatisfiable extends OnboardSvDownloadResponse
  }
}

class SvStreamClient(host: String = "https://example.com")(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) {
  import SvStreamClient.OnboardSvDownloadResponse

  val basePath: String = "/api/sv"

  private[this] def makeRequest[T: ToEntityMarshaller](
      method: HttpMethod,
      uri: Uri,
      headers: scala.collection.immutable.Seq[HttpHeader],
      entity: T,
      protocol: HttpProtocol,
  ): EitherT[Future, Either[Throwable, HttpResponse], HttpRequest] = {
    EitherT(
      Marshal(entity)
        .to[RequestEntity]
        .map[Either[Either[Throwable, HttpResponse], HttpRequest]] { entity =>
          Right(
            HttpRequest(
              method = method,
              uri = uri,
              headers = headers,
              entity = entity,
              protocol = protocol,
            )
          )
        }
        .recover({ case t =>
          Left(Left(t))
        })
    )
  }

  def onboardSvDownload(
      id: String,
      headers: List[HttpHeader] = Nil,
  ): EitherT[Future, Either[Throwable, HttpResponse], OnboardSvDownloadResponse] = {
    makeRequest(
      HttpMethods.GET,
      host + basePath + "/v0/onboard/sv/download/" + Formatter.addPath(id),
      headers,
      HttpEntity.Empty,
      HttpProtocols.`HTTP/1.1`,
    ).flatMap(req =>
      EitherT(
        httpClient(req)
          .flatMap[Either[Either[Throwable, HttpResponse], OnboardSvDownloadResponse]](resp => {
            val reprDigest = resp.headers.find(_.is("repr-digest")).map(_.value)
            resp.status match {
              case StatusCodes.OK =>
                FastFuture.successful(
                  Right(OnboardSvDownloadResponse.OK(resp.entity, reprDigest))
                )
              case StatusCodes.PartialContent =>
                FastFuture.successful(
                  Right(
                    OnboardSvDownloadResponse.PartialContent(
                      resp.entity,
                      resp.header[`Content-Range`].map(_.contentRange),
                      reprDigest,
                    )
                  )
                )
              case StatusCodes.RangeNotSatisfiable =>
                resp
                  .discardEntityBytes()
                  .future()
                  .map(_ => Right(OnboardSvDownloadResponse.RangeNotSatisfiable))
              case _ => FastFuture.successful(Left(Right(resp)))
            }
          })
          .recover({ case e: Throwable => Left(Left(e)) })
      )
    )
  }
}
