// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing
import io.grpc.{Metadata, *}

/** Inspects authorization header, and stores information from decoded access tokens
  *
  * Uses NoTracing, as its called directly by the gRPC library when handing network requests,
  * where we can't inject a tracing context.
  */
final class AuthInterceptor(
    verifier: SignatureVerifier,
    override protected val loggerFactory: NamedLoggerFactory,
) extends ServerInterceptor
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
      encodedToken <- Either.cond(
        authHeaderValue.startsWith(AuthInterceptor.BEARER_PREFIX),
        authHeaderValue.stripPrefix(AuthInterceptor.BEARER_PREFIX),
        s"Auth header did not start with bearer  prefix '$AuthInterceptor.BEARER_PREFIX'",
      )
      decodedToken <- verifier.verify(encodedToken)
      damlUser <- JwtClaims.getDamlUser(decodedToken).toRight("No daml user found in token")
    } yield damlUser

    val ctx = Context.current

    tokenPayloadE match {
      case Right(damlUser) => {
        logger.debug(s"Decoded token with subject = $damlUser")
        val newCtx = ctx.withValue(AuthInterceptor.SUBJECT_KEY, damlUser)

        Contexts.interceptCall(
          newCtx,
          call,
          headers,
          nextListener,
        )
      };
      case Left(error) => {
        logger.info(s"Could not validate token: $error")
        val status = com.google.rpc.Status
          .newBuilder()
          .setCode(com.google.rpc.Code.UNAUTHENTICATED.getNumber)
          .build()

        val err = io.grpc.protobuf.StatusProto.toStatusRuntimeException(status)
        call.close(err.getStatus, err.getTrailers)
        new ServerCall.Listener[ReqT]() {}
      }
    }
  }
}

object AuthInterceptor {
  val BEARER_PREFIX = "Bearer "
  val SUBJECT_KEY = Context.key[String]("AuthServiceDecodedClaim")
  val AUTHORIZATION_KEY = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

  def extractSubjectFromContext(): Option[String] = Option(
    SUBJECT_KEY.get()
  ) // wrapped in Option because getter could return null
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
          headers.put(AuthInterceptor.AUTHORIZATION_KEY, s"${AuthInterceptor.BEARER_PREFIX}${jwt}")
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
