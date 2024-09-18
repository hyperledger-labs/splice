// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client

import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import com.digitalasset.canton.tracing.TraceContext

object TraceContextPropagation {
  implicit class TraceContextExtension(tc: TraceContext) {
    def propagate(
        headers: List[HttpHeader]
    ): List[HttpHeader] = {
      tc.asW3CTraceContext
        .map { w3Ctx =>
          val headersMap = w3Ctx.asHeaders
          val w3CtxHeaders = headersMap.map { case (name, value) =>
            RawHeader(name, value)
          }
          headers.filterNot(h => headersMap.contains(h.name)) ++ w3CtxHeaders.toSeq
        }
        .getOrElse(headers)
    }
  }
}
