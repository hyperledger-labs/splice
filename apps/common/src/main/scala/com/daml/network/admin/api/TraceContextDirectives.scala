package com.daml.network.admin.api

import akka.http.scaladsl.server.{Directive, Directive1}
import com.digitalasset.canton.tracing.TraceContext

object TraceContextDirectives {
  def newTraceContext: Directive1[TraceContext] =
    Directive { inner => ctx =>
      TraceContext.withNewTraceContext { traceContext =>
        inner(Tuple1(traceContext))(ctx)
      }
    }
}
