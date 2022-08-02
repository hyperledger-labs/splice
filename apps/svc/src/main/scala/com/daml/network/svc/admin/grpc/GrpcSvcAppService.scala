package com.daml.network.svc.admin.grpc

import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.admin.SvcAutomationService
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcAppServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    connection: CoinLedgerConnection,
    svcUserName: String,
    protected val loggerFactory: NamedLoggerFactory,
    svcAutomationConstructor: PartyId => SvcAutomationService,
    override val timeouts: ProcessingTimeout,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcAppServiceGrpc.SvcAppService
    with Spanning
    with NamedLogging
    with FlagCloseableAsync {

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("svcAutomation", svcAutomation.foreach(_.close()))
  )

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var svcAutomation: Option[SvcAutomationService] = None

  override def initialize(request: Empty): Future[v0.InitializeResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyId <- connection.getOrAllocateParty(svcUserName)
        _ <- connection.uploadDarFile(CoinUtil) // TODO(i353) move away from dar upload during init
        _ <- CoinUtil.setupApp(svcPartyId, connection)
        _ = logger.info(s"App is initialized")
        _ = svcAutomation = Some(svcAutomationConstructor(svcPartyId))
      } yield v0.InitializeResponse(svcPartyId.toProtoPrimitive)
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

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getUser(svcUserName).flatMap {
        case None =>
          Future.successful(
            v0.GetDebugInfoResponse(
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
          } yield v0.GetDebugInfoResponse(
            svcUser = svcUserName,
            svcParty = partyId.toProtoPrimitive,
            coinPackageId = CoinUtil.packageId,
            coinRulesCids = coinRulesCids,
          )
      }
    }

  override def getValidatorConfig(request: Empty): Future[v0.GetValidatorConfigResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getUser(svcUserName).flatMap {
        case None =>
          Future.failed(new RuntimeException("SVC app not yet initialized"))
        case Some(partyId) =>
          Future.successful(
            v0.GetValidatorConfigResponse(
              svcParty = partyId.toProtoPrimitive
            )
          )
      }
    }
}
