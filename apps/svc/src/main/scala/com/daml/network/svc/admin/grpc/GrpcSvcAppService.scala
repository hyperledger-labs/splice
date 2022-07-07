package com.daml.network.svc.admin.grpc

import com.daml.network.examples.v0.{SvcAppServiceGrpc, SomeDummySvcRequest, SomeDummySvcResponse}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(protected val loggerFactory: NamedLoggerFactory)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcAppServiceGrpc.SvcAppService
    with Spanning
    with NamedLogging {
  override def dummySvcFunction(request: SomeDummySvcRequest): Future[SomeDummySvcResponse] =
    withSpanFromGrpcContext("GrpcSVCService") { implicit traceContext => span =>
      // spans can be used to, e.g., measure how long a certain function takes on average and visualize code flows.
      // We can likely mostly ignore spans at first.
      request.someString.foreach(span.setAttribute("dummyString", _))
      // trace context automatically included here
      logger.info(s"Received dummy request $request")
      Future.successful(SomeDummySvcResponse(request.someNumber - 1))
    }
}
