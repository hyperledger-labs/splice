package com.daml.network.svc.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.v0.{GetDebugInfoResponse, GetValidatorConfigResponse, SvcAppServiceGrpc}
import com.daml.network.util.{CoinUtil, UploadablePackage}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.network.CC
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    connection: CoinLedgerConnection,
    svcUserName: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcAppServiceGrpc.SvcAppService
    with Spanning
    with NamedLogging {

  override def initialize(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyId <- connection.getOrAllocateParty(svcUserName)
        _ <- connection.uploadDarFile(CoinUtil) // TODO(i353) move away from dar upload during init
        _ <- CoinUtil.setupApp(svcPartyId, connection)
        _ = logger.info(s"App is initialized")
      } yield Empty()
    }

  override def openNextRound(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      Future.failed(new RuntimeException("Not implemented"))
    }

  // TODO(M1-90): This should not run concurrently with `openNextRound`
  // Both calls are non-atomic read-modify-write operations on the set of open/issuing mining rounds
  override def acceptValidators(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyIdO <- connection.getUser(svcUserName)
        svcPartyId = svcPartyIdO.getOrElse(
          sys.error(s"User $svcUserName not set up, did you forget to initialize the app?")
        )
        _ <- CoinUtil.acceptCoinRulesRequests(svcPartyId, connection, logger)
      } yield Empty()
    }

  override def getDebugInfo(request: Empty): Future[GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getUser(svcUserName).flatMap {
        case None =>
          Future.successful(
            GetDebugInfoResponse(
              svcUser = svcUserName
            )
          )
        case Some(partyId) =>
          val coinRulesTid = CoinUtil.templateId(CC.CoinRules.CoinRules.id)
          for {
            coinRulesCids <- connection
              .activeContracts(
                LedgerConnection.transactionFilterByParty(Map(partyId -> Seq(coinRulesTid)))
              )
              .map(_._1.map(_.contractId))
          } yield GetDebugInfoResponse(
            svcUser = svcUserName,
            svcParty = partyId.toProtoPrimitive,
            coinPackageId = CoinUtil.packageId,
            coinRulesCids = coinRulesCids,
          )
      }
    }

  override def getValidatorConfig(request: Empty): Future[GetValidatorConfigResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getUser(svcUserName).flatMap {
        case None =>
          Future.failed(new RuntimeException("SVC app not yet initialized"))
        case Some(partyId) =>
          Future.successful(
            GetValidatorConfigResponse(
              svcParty = partyId.toProtoPrimitive
            )
          )
      }
    }
}
