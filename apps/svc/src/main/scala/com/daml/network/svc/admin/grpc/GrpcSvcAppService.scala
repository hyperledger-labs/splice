package com.daml.network.svc.admin.grpc

import cats.implicits._
import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.ledger.client.binding.{Contract, Primitive, TemplateCompanion}
import com.daml.network.codegen.CC
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.svc.store.SvcEventsStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.svc.{SvcApp, v0}
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    ledgerClient: CoinLedgerClient,
    svcUserName: String,
    store: SvcEventsStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcSvcAppService")

  import GrpcSvcAppService._

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getOptionalPrimaryParty(svcUserName).flatMap {
        case None =>
          Future.successful(
            v0.GetDebugInfoResponse(
              svcUser = svcUserName
            )
          )
        case Some(partyId) =>
          for {
            coinRulesCids <- connection
              .activeContracts(partyId, CC.CoinRules.CoinRules)
              .map(_.map(_.contractId))
          } yield v0.GetDebugInfoResponse(
            svcUser = svcUserName,
            svcPartyId = Proto.encode(partyId),
            coinPackageId = SvcApp.coinPackage.packageId,
            coinRulesContractIds = coinRulesCids.map(Proto.encode(_)),
          )
      }
    }

  // NOTE: the commands below have not been converted to use the store, as they'll go away when we automation issuance
  override def openRound(request: v0.OpenRoundRequest): Future[v0.OpenRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        price = Proto.tryDecode(Proto.BigDecimal)(request.coinPrice)
        cmd = CC.CoinRules.CoinRules
          .key(svc.toPrim)
          .exerciseCoinRules_MiningRound_Open(price)
        cid <- connection.submitWithResult(Seq(svc), Seq.empty, cmd)
      } yield v0.OpenRoundResponse(Proto.encode(cid))
    }

  override def startClosingRound(
      request: v0.StartClosingRoundRequest
  ): Future[v0.StartClosingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        openRound <- getRound(CC.Round.OpenMiningRound)(_.round)(svc, request.round)
        cmd =
          CC.CoinRules.CoinRules
            .key(svc.toPrim)
            .exerciseCoinRules_MiningRound_StartClosing(openRound)
        cid <-
          connection.submitWithResult(Seq(svc), Seq.empty, cmd)
      } yield v0.StartClosingRoundResponse(Proto.encode(cid))
    }

  override def startIssuingRound(
      request: v0.StartIssuingRoundRequest
  ): Future[v0.StartIssuingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        closingRound <- getRound(CC.Round.ClosingMiningRound)(_.round)(svc, request.round)
        rewards <- queryRewards(svc, request.round)
        totalBurn = rewards.totalBurn
        cmd =
          CC.CoinRules.CoinRules
            .key(svc.toPrim)
            .exerciseCoinRules_MiningRound_StartIssuing(closingRound, totalBurn)
        cid <-
          connection.submitWithResult(Seq(svc), Seq.empty, cmd)
      } yield v0.StartIssuingRoundResponse(Proto.encode(totalBurn), Proto.encode(cid))
    }

  override def closeRound(request: v0.CloseRoundRequest): Future[v0.CloseRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        issuingRound <- getRound(CC.Round.IssuingMiningRound)(_.round)(svc, request.round)
        totals = getTotalsPerRound(request.round)
        cmd =
          CC.CoinRules.CoinRules
            .key(svc.toPrim)
            .exerciseCoinRules_MiningRound_Close(
              issuingRound,
              totals.transferFees,
              totals.adminFees,
              totals.holdingFees,
              totals.transferInputs,
              totals.nonSelfTransferOutputs,
              totals.selfTransferOutputs,
            )
        cid <-
          connection.submitWithResult(Seq(svc), Seq.empty, cmd)
      } yield v0.CloseRoundResponse(Proto.encode(cid))
    }

  private case class RoundTotals(
      transferFees: BigDecimal = 0.0,
      adminFees: BigDecimal = 0.0,
      holdingFees: BigDecimal = 0.0,
      transferInputs: BigDecimal = 0.0,
      nonSelfTransferOutputs: BigDecimal = 0.0,
      selfTransferOutputs: BigDecimal = 0.0,
  )

  private def getTotalsPerRound(round: Long): RoundTotals = {
    val transfers = store.getTransferSummariesPerRound(round)
    transfers.foldLeft(RoundTotals())((t, transfer) => {
      RoundTotals(
        t.transferFees + transfer.totalTransferFees,
        t.adminFees + transfer.senderAdminFees,
        t.holdingFees + transfer.senderHoldingFees,
        t.transferInputs + transfer.inQuantity,
        t.nonSelfTransferOutputs + transfer.nonSelfOutQuantity,
        t.selfTransferOutputs + transfer.selfOutQuantity,
      )
    })
  }

  override def archiveRound(request: v0.ArchiveRoundRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        closedRound <- getRound(CC.Round.ClosedMiningRound)(_.round)(svc, request.round)
        cmd =
          CC.CoinRules.CoinRules
            .key(svc.toPrim)
            .exerciseCoinRules_MiningRound_Archive(closedRound)
        _ <- connection.submitWithResult(Seq(svc), Seq.empty, cmd)
      } yield Empty()
    }

  /** Query the ACS for the given template and filter by round. Returns a map from the validator party
    * to the corresponding contract id.
    * Fails if there is more than one round for a given validator.
    */
  private def getRound[T](companion: TemplateCompanion[T])(
      getRound: T => CC.Round.Round
  )(svc: PartyId, round: Long): Future[Primitive.ContractId[T]] =
    for {
      allRounds <- connection.activeContracts(svc, companion)
    } yield {
      val filteredRounds = allRounds.filter { case roundContract =>
        val roundId = getRound(roundContract.value)
        roundId.number === round
      }
      require(
        filteredRounds.length == 1,
        s"Expected one round but got ${filteredRounds.length} rounds $filteredRounds",
      )
      filteredRounds(0).contractId
    }

  private def roundResultsToProto[T](
      m: Map[Primitive.Party, Primitive.ContractId[T]]
  ): Map[String, String] =
    m.map { case (v, cid) => Proto.encode(v) -> Proto.encode(cid) }

  /** Given a set of submission results, decode them to a map of validator party to
    * contract id of the given T.
    * Fails if there is not exactly one create for the given template.
    */
  private def decodeRoundResults[T](companion: TemplateCompanion[T])(
      getValidator: T => Primitive.Party
  )(
      results: Seq[SubmitAndWaitForTransactionResponse]
  ): Map[Primitive.Party, Primitive.ContractId[T]] =
    results.map { case r =>
      val rounds = DecodeUtil.decodeAllCreated(companion)(r.getTransaction)
      require(rounds.length == 1, s"Expected one round but found ${rounds.length} rounds $rounds")
      val round = rounds(0)
      getValidator(round.value) -> round.contractId
    }.toMap

  /** Query the open reward contracts for a given round. This should only be used
    * on a ClosingMiningRound.
    */
  private def queryRewards(p: PartyId, round: Long): Future[RoundRewards] = for {
    activeContracts <- connection.activeContracts(
      LedgerConnection.transactionFilterByParty(
        Map(p -> Seq(CC.Coin.AppReward.id, CC.Coin.ValidatorReward.id))
      )
    )
  } yield {
    val appRewards =
      activeContracts.flatMap(ev => DecodeUtil.decodeCreated(CC.Coin.AppReward)(ev))
    val validatorRewards =
      activeContracts.flatMap(ev => DecodeUtil.decodeCreated(CC.Coin.ValidatorReward)(ev))
    RoundRewards(
      round = round,
      appRewards = appRewards.filter(c => c.value.round.number === round),
      validatorRewards = validatorRewards.filter(c => c.value.round.number === round),
    )
  }
}

object GrpcSvcAppService {

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      appRewards: Seq[Contract[CC.Coin.AppReward]],
      validatorRewards: Seq[Contract[CC.Coin.ValidatorReward]],
  ) {

    /** Calculate the total burn for the given round based on the rewards issued in that round.
      */
    def totalBurn: BigDecimal =
      appRewards.map(_.value.quantity).sum + validatorRewards.map(_.value.quantity).sum
  }
}
