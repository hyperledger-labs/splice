// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.http

import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.http.v0.sv_admin as httpAdmin
import org.lfdecentralizedtrust.splice.http.v0.sv_operator as httpOperator
import org.lfdecentralizedtrust.splice.http.v0.sv_public as httpPublic
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

object SvHttpClient {

  abstract class BaseCommandPublic[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = httpPublic.SvPublicClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = {
      httpPublic.SvPublicClient.httpClient(HttpClientBuilder().buildClient(), host)
    }
  }

  abstract class BaseCommandOperator[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = httpOperator.SvOperatorClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = {
      httpOperator.SvOperatorClient.httpClient(HttpClientBuilder().buildClient(), host)
    }
  }

  abstract class BaseCommandAdmin[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = httpAdmin.SvAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = {
      httpAdmin.SvAdminClient.httpClient(HttpClientBuilder().buildClient(), host)
    }
  }

}
