package com.daml.network.svc.admin.grpc

import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.Codec
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.Future

class GrpcSvcAppService(
    svcUserName: String,
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvcStore],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    tracer: Tracer
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val svcStore = svcStoreWithIngestion.store

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      Future.successful(
        v0.GetDebugInfoResponse(
          svcUser = svcUserName,
          svcPartyId = Codec.encode(svcStore.svcParty),
        )
      )
    }
}
