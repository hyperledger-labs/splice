// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import org.lfdecentralizedtrust.splice.http.v0.Implicits.*
import org.lfdecentralizedtrust.splice.http.v0.PekkoHttpImplicits.*
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.http.scaladsl.model.ResponseEntity
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.implicits.*
import scala.concurrent.{ExecutionContext, Future}

/** We failed to convince Guardrail to not try and decode a binary stream as a json, so we ended up disabling client
  * code generation for the streaming endpoints, and just manually create it (heavily based on the guardrail generated clients)
  */
object ScanStreamClient {
  def apply(host: String = "https://example.com")(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ): ScanStreamClient =
    new ScanStreamClient(host = host)(httpClient = httpClient, ec = ec, mat = mat)
  def httpClient(
      httpClient: HttpRequest => Future[HttpResponse],
      host: String = "https://example.com",
  )(implicit ec: ExecutionContext, mat: Materializer): ScanStreamClient =
    new ScanStreamClient(host = host)(httpClient = httpClient, ec = ec, mat = mat)
}
class ScanStreamClient(host: String = "https://example.com")(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) {
  val basePath: String = "/api/scan"
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
  val bulkStorageDownloadNotFoundDecoder = {
    structuredJsonEntityUnmarshaller.flatMap(_ =>
      _ =>
        json =>
          io.circe
            .Decoder[ErrorResponse]
            .decodeJson(json)
            .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  def ok(
      entity: ResponseEntity
  ): Future[Either[Either[Throwable, HttpResponse], BulkStorageDownloadResponse]] =
    Future.successful(Right(BulkStorageDownloadResponse.OK(entity)))

  def notFound(
      resp: HttpResponse
  ): Future[Either[Either[Throwable, HttpResponse], BulkStorageDownloadResponse]] =
    Unmarshal(resp.entity)
      .to[ErrorResponse](
        bulkStorageDownloadNotFoundDecoder,
        implicitly,
        implicitly,
      )
      .map(x => Right(BulkStorageDownloadResponse.NotFound(x)))

  def bulkStorageDownload(
      objectKey: String,
      headers: List[HttpHeader] = Nil,
  ): EitherT[Future, Either[Throwable, HttpResponse], BulkStorageDownloadResponse] = {
    val allHeaders = headers ++ scala.collection.immutable.Seq[Option[HttpHeader]]().flatten
    makeRequest(
      HttpMethods.GET,
      host + basePath + "/v0/history/bulk/download/" + Formatter.addPath(objectKey),
      allHeaders,
      HttpEntity.Empty,
      HttpProtocols.`HTTP/1.1`,
    ).flatMap(req =>
      EitherT(
        httpClient(req)
          .flatMap(resp =>
            resp.status match {
              case StatusCodes.OK => ok(resp.entity)
              case StatusCodes.NotFound => notFound(resp)
              case _ => FastFuture.successful(Left(Right(resp)))
            }
          )
          .recover({ case e: Throwable => Left(Left(e)) })
      )
    )
  }
}
sealed abstract class BulkStorageDownloadResponse {
  def fold[A](
      handleOK: ResponseEntity => A,
      handleNotFound: ErrorResponse => A,
  ): A = this match {
    case x: BulkStorageDownloadResponse.OK =>
      handleOK(x.value)
    case x: BulkStorageDownloadResponse.NotFound =>
      handleNotFound(x.value)
  }
}
object BulkStorageDownloadResponse {
  case class OK(value: ResponseEntity) extends BulkStorageDownloadResponse
  case class NotFound(
      value: ErrorResponse
  ) extends BulkStorageDownloadResponse
}
