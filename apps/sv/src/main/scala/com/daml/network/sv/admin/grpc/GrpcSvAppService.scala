package com.daml.network.sv.admin.grpc

import com.daml.network.environment.CoinLedgerClient
import com.daml.network.sv.store.SvStore
import com.daml.network.sv.v0
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.sv.SvApp

class GrpcSvAppService(
    ledgerClient: CoinLedgerClient,
    svUserName: String,
    store: SvStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvServiceGrpc.SvService
    with Spanning
    with NamedLogging {

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvAppService") { _ => _ =>
      for {
        domains <- store.domains.listConnectedDomains().map(_.values.toSeq)
        // TODO (M3-18) either choose the correct domain or fold over the
        // store's ACSes instead (for which there should be 1/domain)
        coinRules <- store.getCoinRules()
        svcRules <- store.getSvcRules()
      } yield v0.GetDebugInfoResponse(
        svUser = svUserName,
        svPartyId = Proto.encode(store.key.svParty),
        svcPartyId = Proto.encode(store.key.svcParty),
        svcGovernancePackageId = SvApp.svcGovernancePackage.packageId,
        coinRulesContractId = Proto.encodeContractId(coinRules.contractId),
        svcRulesContractId = Proto.encodeContractId(svcRules.contractId),
      )
    }

  override def listConnectedDomains(request: Empty): Future[v0.ListConnectedDomainsResponse] =
    withSpanFromGrpcContext("GrpcSvAppService") { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield {
        v0.ListConnectedDomainsResponse(Some(Proto.encode(domains)))
      }
    }
}
