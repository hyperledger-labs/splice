package com.daml.network.sv.admin.grpc

import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.sv.store.SvStore
import com.daml.network.sv.v0
import com.daml.network.sv.v0.SvServiceGrpc
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvAppService(
    ledgerClient: CoinLedgerClient,
    svUserName: String,
    store: SvStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvServiceGrpc.SvService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection()

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvAppService") { _ => _ =>
      for {
        coinRulesCids <- connection
          .activeContracts(store.key.svParty, cc.coin.CoinRules.COMPANION)
          .map(_.map(_.id))
      } yield v0.GetDebugInfoResponse(
        svUser = svUserName,
        svPartyId = Proto.encode(store.key.svParty),
        coinRulesContractIds = coinRulesCids.map(Proto.encodeContractId(_)),
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
