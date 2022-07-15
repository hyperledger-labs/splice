package com.daml.network.svc.admin.grpc

import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.examples.v0.{GetDebugInfoResponse, SvcAppServiceGrpc}
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.CoinRules.{CoinConfig, CoinRules}
import com.digitalasset.network.OpenBusiness.Fees
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

  // TODO(Robert): move to some config, maybe SvcAppParameters
  private def initialCoinRules(partyId: PartyId) = CoinRules(
    svc = partyId.toPrim,
    config = CoinConfig(
      createFee = Fees.FixedFee(0.001),
      updateFee = Fees.FixedFee(0.001),
      holdingFee = Fees.RatePerRound(0.001),
      transferFee = Fees.SteppedRate(0, Seq.empty),
      coinsIssuedPerRound = 1,
      minRewardQuantity = 0.001,
      svcIssuanceRatio = 0.5,
      maxNumInputs = 10,
      maxNumOutputs = 10,
      maxPayloadLength = 1000,
    ),
    obs = partyId.toPrim,
  )

  override def initialize(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        // TODO(Robert): Factor out user/party allocation and make it robust (current implementation is racy)
        existingPartyId <- connection.getUser(svcUserName)
        partyId <- existingPartyId.fold[Future[PartyId]](connection.bootstrapUser(svcUserName))(
          Future.successful
        )
        _ = logger.info(s"User $svcUserName and party $partyId are allocated")

        _ <- connection.uploadDarFile(
          CoinUtil.packageId,
          ByteString.readFrom(CoinUtil.coinDarInputStream()),
        )
        _ = logger.info(s"Package ${CoinUtil.packageId} is uploaded")

        rules = initialCoinRules(partyId)
        tid <- connection.submitCommand(Seq(partyId), Seq.empty, Seq(rules.create.command))
        _ = logger.info(s"Initial coin rules $rules created in transaction $tid")
      } yield Empty()
    }

  override def openNextRound(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      Future.failed(new RuntimeException("Not implemented"))
    }

  override def acceptValidators(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      Future.failed(new RuntimeException("Not implemented"))
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
          for {
            coinRulesCids <- connection
              .activeContracts(LedgerConnection.transactionFilter(partyId.toPrim))
              .map(_._1.map(_.contractId))
          } yield GetDebugInfoResponse(
            svcUser = svcUserName,
            svcParty = partyId.toProtoPrimitive,
            coinPackageId = CoinUtil.packageId,
            coinRulesCids = coinRulesCids,
          )
      }
    }
}
