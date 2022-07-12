package com.daml.network.validator.admin.grpc

import com.daml.network.examples.v0.{ValidatorAppServiceGrpc, SomeDummyRequest, SomeDummyResponse}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(protected val loggerFactory: NamedLoggerFactory)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {
  override def dummyFunction(request: SomeDummyRequest): Future[SomeDummyResponse] =
    withSpanFromGrpcContext("GrpcDummyService") { implicit traceContext => span =>
      // spans can be used to, e.g., measure how long a certain function takes on average and visualize code flows.
      // We can likely mostly ignore spans at first.
      request.someString.foreach(span.setAttribute("dummyString", _))
      // trace context automatically included here
      logger.info(s"Received dummy request $request")
      Future.successful(SomeDummyResponse(request.someNumber + 1))
    }
}
