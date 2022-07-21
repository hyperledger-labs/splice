package com.daml.network.svc.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.examples.v0.{
  GetDebugInfoResponse,
  GetValidatorConfigResponse,
  SvcAppServiceGrpc,
}
import com.daml.network.util.CoinUtil
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
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcAppServiceGrpc.SvcAppService
    with Spanning
    with NamedLogging {

  // TODO(Robert): move to some config, maybe SvcAppParameters
  private val svcUserName = "svc"

  // TODO(Robert): Factor out user/party allocation and make it robust (current implementation is racy)
  private def getOrAllocateParty(
      username: String,
      connection: CoinLedgerConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] = {
    for {
      existingPartyId <- connection.getUser(username)
      partyId <- existingPartyId.fold[Future[PartyId]](connection.bootstrapUser(username))(
        Future.successful
      )
      _ = logger.info(s"User $username and party $partyId are allocated")
    } yield partyId
  }

  // TODO(Robert): Factor out package uploading and make it robust
  private def assertPackageIsUploaded(
      connection: CoinLedgerConnection
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      _ <- connection.uploadDarFile(
        CoinUtil.packageId,
        ByteString.readFrom(CoinUtil.coinDarInputStream()),
      )
      // TODO(M1-90): The ledger API does not block until the package is vetted.
      //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
      _ = Threading.sleep(1000)
      _ = logger.info(s"Package ${CoinUtil.packageId} is uploaded")
    } yield ()
  }

  override def initialize(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyId <- getOrAllocateParty(svcUserName, connection)
        _ <- assertPackageIsUploaded(connection)
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
