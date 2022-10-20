// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.auth0.jwt.JWT
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing
import io.grpc.{Metadata, *}

import scala.util.Try

/** Inspects authorization header, and stores information from decoded access tokens
  *
  * Uses NoTracing, as its called directly by the gRPC library when handing network requests,
  * where we can't inject a tracing context.
  */
final class AuthInterceptor(override protected val loggerFactory: NamedLoggerFactory)
    extends ServerInterceptor
    with NamedLogging
    with NoTracing {
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      nextListener: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] = {

    val tokenPayloadE = for {
      authHeaderValue <- Option(headers.get(AuthInterceptor.AUTHORIZATION_KEY))
        .toRight(s"No ${AuthInterceptor.AUTHORIZATION_KEY} header found")
      // TODO(i1012) - make "Bearer $token" format mandatory
      encodedToken = authHeaderValue.stripPrefix("Bearer ")
      // TODO(i1011) - use JWT.require for sig verification
      decodedToken <- Try(JWT.decode(encodedToken)).toEither.left.map(_.toString)
    } yield decodedToken

    val ctx = Context.current

    tokenPayloadE match {
      case Right(jwt) => {
        val subject = Option(jwt.getSubject)
        logger.debug(s"Decoded token with subject = $subject")
        val newCtx = ctx.withValue(AuthInterceptor.SUBJECT_KEY, subject)

        Contexts.interceptCall(
          newCtx,
          call,
          headers,
          nextListener,
        )
      };
      case Left(error) => {
        logger.debug(s"Could not decode token: $error")
        // TODO(i1012) - strictly enforce token decoding errors
        Contexts.interceptCall(ctx, call, headers, nextListener)
      }
    }
  }
}

object AuthInterceptor {
  val SUBJECT_KEY = Context.key[Option[String]]("AuthServiceDecodedClaim")
  val AUTHORIZATION_KEY = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

  def extractSubjectFromContext(): Option[String] = {
    val claimSet = SUBJECT_KEY.get()
    if (claimSet == null)
      None
    else
      claimSet
  }
}

import io.grpc.CallCredentials
import io.grpc.Status
import java.util.concurrent.Executor

class JwtCallCredential(val jwt: String) extends CallCredentials {
  override def applyRequestMetadata(
      requestInfo: CallCredentials.RequestInfo,
      executor: Executor,
      metadataApplier: CallCredentials.MetadataApplier,
  ): Unit = {
    executor.execute(new Runnable() {
      override def run() = {
        try {
          val headers = new Metadata
          headers.put(AuthInterceptor.AUTHORIZATION_KEY, jwt)
          metadataApplier.apply(headers)
        } catch {
          case e: Throwable =>
            metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e))
        }
      }
    })
  }

  override def thisUsesUnstableApi(): Unit = {}
}
