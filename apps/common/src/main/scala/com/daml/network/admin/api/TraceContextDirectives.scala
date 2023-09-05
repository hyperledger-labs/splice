package com.daml.network.admin.api

import akka.http.scaladsl.server.{Directive, Directive1}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.tracing.W3CTraceContext

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
          inner(Tuple1(traceContext))(ctx)
        }
        .getOrElse {
          TraceContext.withNewTraceContext { traceContext =>
            inner(Tuple1(traceContext))(ctx)
          }
        }
    }
  }
}
