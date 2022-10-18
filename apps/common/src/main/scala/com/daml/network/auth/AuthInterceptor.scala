// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.auth0.jwt.JWT
import io.grpc.{Metadata, _}

import scala.util.Try

final class AuthInterceptor() extends ServerInterceptor {
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      nextListener: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] = {
    // TODO(i1012) - switch to "Bearer $token" format for the value of AUTHORIZATION_KEY
    val token = headers.get(AuthInterceptor.AUTHORIZATION_KEY)

    // TODO(i1011) - use JWT.require for sig verification
    val jwtOpt = Try(JWT.decode(token)).toOption

    val ctx = Context.current

    jwtOpt match {
      case Some(jwt) => {
        val newCtx = ctx.withValue(AuthInterceptor.SUBJECT_KEY, Option(jwt.getSubject()))

        Contexts.interceptCall(
          newCtx,
          call,
          headers,
          nextListener,
        )
      };
      case None => {
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
