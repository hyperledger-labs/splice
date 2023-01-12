package com.daml.network.svc.admin.grpc

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, DedupOffset}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.{
  GrantFeaturedAppRightRequest,
  GrantFeaturedAppRightResponse,
  SvcServiceGrpc,
  WithdrawFeaturedAppRightRequest,
}
import com.daml.network.svc.{SvcApp, v0}
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    ledgerClient: CoinLedgerClient,
    svcUserName: String,
    store: SvcStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcSvcAppService")

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      for {
        coinRulesCids <- connection
          .activeContracts(store.svcParty, cc.coin.CoinRules.COMPANION)
          .map(_.map(_.id))
      } yield v0.GetDebugInfoResponse(
        svcUser = svcUserName,
        svcPartyId = Proto.encode(store.svcParty),
        coinPackageId = SvcApp.coinPackage.packageId,
        coinRulesContractIds = coinRulesCids.map(Proto.encodeContractId(_)),
      )
    }

  /** Grant a featured app right to an app provider
    */
  override def grantFeaturedAppRight(
      request: GrantFeaturedAppRightRequest
  ): Future[GrantFeaturedAppRightResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      val svcParty = store.svcParty
      val providerParty = PartyId.tryFromProtoPrimitive(request.appProvider)
      for {
        result <- store.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(off, None) =>
            connection.submitWithResult(
              actAs = Seq(svcParty),
              readAs = Seq.empty,
              update = new FeaturedAppRight(
                store.svcParty.toProtoPrimitive,
                request.appProvider,
              ).create(),
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.svc.grantFeaturedAppRight",
                Seq(svcParty, providerParty),
              ),
              deduplicationConfig = DedupOffset(off),
            )
          case QueryResult(_, Some(_)) =>
            logger.info("Rejecting duplicate featured app requests")
            throw new StatusRuntimeException(
              Status.ALREADY_EXISTS.withDescription(
                s"App provider ${request.appProvider} already has a featured app right"
              )
            )
        }
      } yield GrantFeaturedAppRightResponse(Proto.encodeContractId(result.contractId))
    }

  /** Withdraw a featured app right from an app provider, with a textual reasoning
    */
  override def withdrawFeaturedAppRight(request: WithdrawFeaturedAppRightRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        _ <- store.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(_, None) =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"No featured app right found for provider ${request.appProvider}"
              )
            )
          case QueryResult(_, Some(c)) =>
            connection.submitWithResultNoDedup(
              actAs = Seq(store.svcParty),
              readAs = Seq.empty,
              update = c.contractId.exerciseFeaturedAppRight_Withdraw(request.reason),
            )
        }
      } yield Empty()
    }

  override def listConnectedDomains(request: Empty): Future[v0.ListConnectedDomainsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield {
        v0.ListConnectedDomainsResponse(Some(Proto.encode(domains)))
      }
    }
}
