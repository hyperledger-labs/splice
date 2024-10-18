// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.http

import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.sv as http
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
