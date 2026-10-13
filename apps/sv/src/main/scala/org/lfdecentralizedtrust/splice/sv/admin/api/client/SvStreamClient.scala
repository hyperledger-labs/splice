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
import org.lfdecentralizedtrust.splice.http.v0.PekkoHttpImplicits.*
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse

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
}

class SvStreamClient(host: String = "https://example.com")(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) {
  import SvStreamClient.{OnboardSvPartyMigrationAuthorizeResponse, OnboardSvSequencerResponse}

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
}
