package com.daml.network.scan.admin.grpc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.v0
import com.daml.network.scan.v0.ScanServiceGrpc
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcScanService(
    connection: CoinLedgerConnection,
    svcUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScanServiceGrpc.ScanService
    with Spanning
    with NamedLogging {

  @nowarn("cat=unused")
  override def getSvcPartyId(request: Empty): Future[v0.GetSvcPartyIdResponse] =
    withSpanFromGrpcContext("GrpcScanService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(svcUser)
      } yield v0.GetSvcPartyIdResponse(party.toProtoPrimitive)
    }
}
