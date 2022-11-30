package com.daml.network.admin.grpc

import com.daml.network.environment.BuildInfo
import com.daml.network.v0
import com.daml.network.v0.VersionServiceGrpc
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcVersionService(
    protected val loggerFactory: NamedLoggerFactory
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends VersionServiceGrpc.VersionService
    with Spanning
    with NamedLogging {

  override def getVersion(request: Empty): Future[v0.GetVersionResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { _ => span =>
      Future(v0.GetVersionResponse(version = BuildInfo.compiledVersion))
    }
}
