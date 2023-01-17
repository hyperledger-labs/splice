package com.daml.network.scan.admin.grpc

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.v0
import com.daml.network.scan.v0.*
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcScanService(
    ledgerClient: CoinLedgerClient,
    store: ScanStore,
    clock: Clock,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScanServiceGrpc.ScanService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcScanService")

  @nowarn("cat=unused")
  override def getSvcPartyId(request: Empty): Future[v0.GetSvcPartyIdResponse] =
    withSpanFromGrpcContext("GrpcScanService") { implicit traceContext => span =>
      Future.successful(v0.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }

  override def getTransferContext(request: Empty): Future[v0.GetTransferContextResponse] =
    withSpanFromGrpcContext("GrpcScanService") { _ => _ =>
      for {
        coinRules <- store.lookupCoinRules()
        now = clock.now
        latestOpen <- store.getLatestOpenMiningRound(now)
        rounds <- store.lookupSubmittableOpenMiningRounds(now)
      } yield {
        v0.GetTransferContextResponse(
          coinRules = coinRules.map(_.toProtoV0),
          latestOpenMiningRound = Some(latestOpen.toProtoV0),
          // TODO(M3-09): consider just removing this attribute - not used except in tests.
          openMiningRounds = rounds.map(_.toProtoV0),
        )
      }
    }

  override def getCoinTransactionDetails(
      request: GetCoinTransactionDetailsRequest
  ): Future[GetCoinTransactionDetailsResponse] = withSpanFromGrpcContext("GrpcScanService") {
    traceContext => span =>
      for {
        tree <- connection.tryGetTransactionTreeById(Seq(store.svcParty), request.transactionId)
      } yield v0.GetCoinTransactionDetailsResponse(
        Some(TransactionTree.fromJavaProto(tree.toProto))
      )
  }

  override def getClosedRounds(request: Empty): Future[GetClosedRoundsResponse] =
    withSpanFromGrpcContext("GrpcScanService") { _ => _ =>
      for {
        rounds <- store.acs.listContracts(roundCodegen.ClosedMiningRound.COMPANION)
      } yield {
        val filteredRounds = rounds.sortWith(_.payload.round.number > _.payload.round.number)
        v0.GetClosedRoundsResponse(filteredRounds.map(r => r.toProtoV0))
      }
    }

  override def listFeaturedAppRights(request: Empty): Future[ListFeaturedAppRightsResponse] =
    withSpanFromGrpcContext("GrpcScanService") { _ => _ =>
      for {
        apps <- store.acs.listContracts(FeaturedAppRight.COMPANION)
      } yield {
        v0.ListFeaturedAppRightsResponse(apps.map(a => a.toProtoV0))
      }
    }

  override def listConnectedDomains(request: Empty): Future[v0.ListConnectedDomainsResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield {
        v0.ListConnectedDomainsResponse(Some(Proto.encode(domains)))
      }
    }
}
