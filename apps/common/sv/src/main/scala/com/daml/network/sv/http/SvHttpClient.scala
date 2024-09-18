// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.http

import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.HttpClient
import com.daml.network.http.v0.sv as http
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

object SvHttpClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = {
      http.SvClient.httpClient(HttpClientBuilder().buildClient(), host)
    }
  }

}
