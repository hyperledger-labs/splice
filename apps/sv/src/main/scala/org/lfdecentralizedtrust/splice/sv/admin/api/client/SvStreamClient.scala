// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import cats.data.EitherT
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.Implicits.*
import org.lfdecentralizedtrust.splice.http.v0.PekkoHttpImplicits.*
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

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

  sealed abstract class OnboardSvPartyMigrationAuthorizeResponse
  object OnboardSvPartyMigrationAuthorizeResponse {

    case class OK(value: Seq[ByteString]) extends OnboardSvPartyMigrationAuthorizeResponse
    case class BadRequest(value: definitions.OnboardSvPartyMigrationAuthorizeErrorResponse)
        extends OnboardSvPartyMigrationAuthorizeResponse
    case class Unauthorized(value: ErrorResponse) extends OnboardSvPartyMigrationAuthorizeResponse
  }

  sealed abstract class OnboardSvSequencerResponse
  object OnboardSvSequencerResponse {
    case class OK(value: Seq[ByteString]) extends OnboardSvSequencerResponse
    case class BadRequest(value: ErrorResponse) extends OnboardSvSequencerResponse
  }

  sealed trait SnapshotPreparation
  object SnapshotPreparation {
    final case class Prepared(id: String) extends SnapshotPreparation
    final case class Pending(state: String, retryAfter: FiniteDuration) extends SnapshotPreparation
  }

  sealed abstract class OnboardSvPartyMigrationPrepareResponse
  object OnboardSvPartyMigrationPrepareResponse {
    case class Accepted(state: String, retryAfter: FiniteDuration)
        extends OnboardSvPartyMigrationPrepareResponse
    case class OK(value: definitions.OnboardSvSnapshotPrepareResponse)
        extends OnboardSvPartyMigrationPrepareResponse
    case class BadRequest(value: definitions.OnboardSvPartyMigrationAuthorizeErrorResponse)
        extends OnboardSvPartyMigrationPrepareResponse
  }

  sealed abstract class OnboardSvDownloadResponse
  object OnboardSvDownloadResponse {
    case class Accepted(state: String, retryAfter: FiniteDuration) extends OnboardSvDownloadResponse
    case class OK(entity: ResponseEntity) extends OnboardSvDownloadResponse
  }
}

class SvStreamClient(host: String = "https://example.com")(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) {
  import SvStreamClient.{
    OnboardSvDownloadResponse,
    OnboardSvPartyMigrationAuthorizeResponse,
    OnboardSvPartyMigrationPrepareResponse,
    OnboardSvSequencerResponse,
  }

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

  private val jsonDecoder = {
    structuredJsonEntityUnmarshaller.flatMap(_ =>
      _ =>
        json =>
          io.circe
            .Decoder[definitions.OnboardSvPartyMigrationAuthorizeErrorResponse]
            .decodeJson(json)
            .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  private val prepareResponseDecoder = {
    structuredJsonEntityUnmarshaller.flatMap(_ =>
      _ =>
        json =>
          io.circe
            .Decoder[definitions.OnboardSvSnapshotPrepareResponse]
            .decodeJson(json)
            .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  private val pendingResponseDecoder = {
    structuredJsonEntityUnmarshaller.flatMap(_ =>
      _ =>
        json =>
          io.circe
            .Decoder[definitions.OnboardSvSnapshotPendingResponse]
            .decodeJson(json)
            .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  private val defaultPendingRetryAfter = 5.seconds

  private def pendingResponse(
      response: HttpResponse
  ): Future[SvStreamClient.SnapshotPreparation.Pending] =
    Unmarshal(response.entity)
      .to[definitions.OnboardSvSnapshotPendingResponse](
        pendingResponseDecoder,
        implicitly,
        implicitly,
      )
      .map { pending =>
        val retryAfter = response.headers
          .find(_.is("retry-after"))
          .flatMap(_.value.toLongOption)
          .filter(_ > 0)
          .map(_.min(Int.MaxValue.toLong).seconds)
          .getOrElse(defaultPendingRetryAfter)
        SvStreamClient.SnapshotPreparation.Pending(pending.state, retryAfter)
      }

  private val errorResponseDecoder = {
    structuredJsonEntityUnmarshaller.flatMap(_ =>
      _ =>
        json =>
          io.circe
            .Decoder[ErrorResponse]
            .decodeJson(json)
            .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  def onboardSvPartyMigrationAuthorize(
      body: definitions.OnboardSvPartyMigrationAuthorizeRequest,
      headers: List[HttpHeader] = Nil,
  ): EitherT[Future, Either[Throwable, HttpResponse], OnboardSvPartyMigrationAuthorizeResponse] = {
    val allHeaders = headers ++ scala.collection.immutable.Seq[Option[HttpHeader]]().flatten
    makeRequest(
      HttpMethods.POST,
      host + basePath + "/v0/onboard/sv/party-migration/authorize",
      allHeaders,
      body,
      HttpProtocols.`HTTP/1.1`,
    ).flatMap(req =>
      EitherT(
        httpClient(req)
          .flatMap(resp =>
            resp.status match {
              case StatusCodes.OK =>
                resp.entity.dataBytes
                  .runWith(Sink.seq)
                  .map(chunks =>
                    Right(OnboardSvPartyMigrationAuthorizeResponse.OK(chunks)): Either[Either[
                      Throwable,
                      HttpResponse,
                    ], OnboardSvPartyMigrationAuthorizeResponse]
                  )
              case StatusCodes.BadRequest =>
                Unmarshal(resp.entity)
                  .to[definitions.OnboardSvPartyMigrationAuthorizeErrorResponse](
                    jsonDecoder,
                    implicitly,
                    implicitly,
                  )
                  .map(x => Right(OnboardSvPartyMigrationAuthorizeResponse.BadRequest(x)))
              case StatusCodes.Unauthorized =>
                Unmarshal(resp.entity)
                  .to[ErrorResponse](errorResponseDecoder, implicitly, implicitly)
                  .map(x => Right(OnboardSvPartyMigrationAuthorizeResponse.Unauthorized(x)))
              case _ => FastFuture.successful(Left(Right(resp)))
            }
          )
          .recover({ case e: Throwable => Left(Left(e)) })
      )
    )
  }

  def onboardSvSequencer(
      body: definitions.OnboardSvSequencerRequest,
      headers: List[HttpHeader] = Nil,
  ): EitherT[Future, Either[Throwable, HttpResponse], OnboardSvSequencerResponse] = {
    val allHeaders = headers ++ scala.collection.immutable.Seq[Option[HttpHeader]]().flatten
    makeRequest(
      HttpMethods.POST,
      host + basePath + "/v0/onboard/sv/sequencer",
      allHeaders,
      body,
      HttpProtocols.`HTTP/1.1`,
    ).flatMap(req =>
      EitherT(
        httpClient(req)
          .flatMap(resp =>
            resp.status match {
              case StatusCodes.OK =>
                resp.entity.dataBytes
                  .runWith(Sink.seq)
                  .map(chunks =>
                    Right(OnboardSvSequencerResponse.OK(chunks)): Either[Either[
                      Throwable,
                      HttpResponse,
                    ], OnboardSvSequencerResponse]
                  )
              case StatusCodes.BadRequest =>
                Unmarshal(resp.entity)
                  .to[ErrorResponse](errorResponseDecoder, implicitly, implicitly)
                  .map(x => Right(OnboardSvSequencerResponse.BadRequest(x)))
              case _ => FastFuture.successful(Left(Right(resp)))
            }
          )
          .recover({ case e: Throwable => Left(Left(e)) })
      )
    )
  }

  def onboardSvPartyMigrationPrepare(
      body: definitions.OnboardSvPartyMigrationAuthorizeRequest,
      headers: List[HttpHeader] = Nil,
  ): EitherT[Future, Either[Throwable, HttpResponse], OnboardSvPartyMigrationPrepareResponse] = {
    makeRequest(
      HttpMethods.POST,
      host + basePath + "/v0/onboard/sv/party-migration/prepare",
      headers,
      body,
      HttpProtocols.`HTTP/1.1`,
    ).flatMap(req =>
      EitherT(
        httpClient(req)
          .flatMap[Either[Either[Throwable, HttpResponse], OnboardSvPartyMigrationPrepareResponse]](
            resp =>
              resp.status match {
                case StatusCodes.Accepted =>
                  pendingResponse(resp)
                    .map(pending =>
                      Right(
                        OnboardSvPartyMigrationPrepareResponse
                          .Accepted(pending.state, pending.retryAfter)
                      )
                    )
                case StatusCodes.OK =>
                  Unmarshal(resp.entity)
                    .to[definitions.OnboardSvSnapshotPrepareResponse](
                      prepareResponseDecoder,
                      implicitly,
                      implicitly,
                    )
                    .map(x => Right(OnboardSvPartyMigrationPrepareResponse.OK(x)))
                case StatusCodes.BadRequest =>
                  Unmarshal(resp.entity)
                    .to[definitions.OnboardSvPartyMigrationAuthorizeErrorResponse](
                      jsonDecoder,
                      implicitly,
                      implicitly,
                    )
                    .map(x => Right(OnboardSvPartyMigrationPrepareResponse.BadRequest(x)))
                case _ => FastFuture.successful(Left(Right(resp)))
              }
          )
          .recover({ case e: Throwable => Left(Left(e)) })
      )
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
          .flatMap[Either[Either[Throwable, HttpResponse], OnboardSvDownloadResponse]](resp =>
            resp.status match {
              case StatusCodes.Accepted =>
                pendingResponse(resp)
                  .map(pending =>
                    Right(OnboardSvDownloadResponse.Accepted(pending.state, pending.retryAfter))
                  )
              case StatusCodes.OK =>
                FastFuture.successful(Right(OnboardSvDownloadResponse.OK(resp.entity)))
              case _ => FastFuture.successful(Left(Right(resp)))
            }
          )
          .recover({ case e: Throwable => Left(Left(e)) })
      )
    )
  }
}
