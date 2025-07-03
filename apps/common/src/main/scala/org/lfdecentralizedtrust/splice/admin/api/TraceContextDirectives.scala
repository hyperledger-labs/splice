// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import org.apache.pekko.http.scaladsl.server
import org.apache.pekko.http.scaladsl.server.{Directive, Directive1, RequestContext, RouteResult}
import org.lfdecentralizedtrust.splice.admin.api.client.TraceContextPropagation.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.tracing.W3CTraceContext

import scala.concurrent.Future

object TraceContextDirectives {

  /** Provides the TraceContext from W3C Trace Context headers,
    * or provides a new TraceContext if the headers are not present.
    *
    * @return
    */
  def withTraceContext: Directive1[TraceContext] = {
    Directive { inner => ctx =>
      val headersMap =
        ctx.request.headers.map(h => h.name -> h.value).toMap

      W3CTraceContext
        .fromHeaders(headersMap)
        .map { w3ctx =>
          val traceContext = w3ctx.toTraceContext
          withTraceResponseHeaders(inner, traceContext, ctx)
        }
        .getOrElse {
          TraceContext.withNewTraceContext("no_w3c_trace_context") { traceContext =>
            withTraceResponseHeaders(inner, traceContext, ctx)
          }
        }
    }
  }

  private def withTraceResponseHeaders(
      inner: Tuple1[TraceContext] => server.Route,
      traceContext: TraceContext,
      ctx: RequestContext,
  ): Future[RouteResult] = {
    // Note: This is similar to the respondWithHeader() directive from the Akka DSL
    inner(Tuple1(traceContext))(ctx).map {
      case RouteResult.Complete(response) =>
        val newResponse = response.withHeaders(traceContext.propagate(response.headers.toList))
        RouteResult.Complete(newResponse)
      case other => other
    }(ctx.executionContext)
  }

}
