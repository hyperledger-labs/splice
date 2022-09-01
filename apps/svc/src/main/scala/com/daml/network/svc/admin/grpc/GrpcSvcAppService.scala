package com.daml.network.svc.admin.grpc

import cats.implicits._
import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.ledger.client.binding.{Contract, Primitive, TemplateCompanion}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.{CoinUtil, Proto}
import com.digitalasset.canton.participant.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.daml.network.codegen.{CC, DA}
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    ledgerClient: CoinLedgerClient,
    svcUserName: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcSvcAppService")

  import GrpcSvcAppService._
  // TODO(M1-90): This should not run concurrently with round management operations.
  // Both are non-atomic read-modify-write operations on the set of mining rounds.
  override def acceptValidators(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyId <- connection.getPrimaryParty(svcUserName)
        _ <- CoinUtil.acceptCoinRulesRequests(svcPartyId, connection, logger)
      } yield Empty()
    }

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
            coinPackageId = CoinUtil.packageId,
            coinRulesContractIds = coinRulesCids.map(Proto.encode(_)),
          )
      }
    }

  override def getValidatorConfig(request: Empty): Future[v0.GetValidatorConfigResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      connection.getOptionalPrimaryParty(svcUserName).flatMap {
        case None =>
          Future.failed(new RuntimeException("SVC app not yet initialized"))
        case Some(partyId) =>
          Future.successful(
            v0.GetValidatorConfigResponse(
              svcPartyId = Proto.encode(partyId)
            )
          )
      }
    }

  override def openRound(request: v0.OpenRoundRequest): Future[v0.OpenRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        validators <- getValidators(svc)
        price = Proto.tryDecode(Proto.BigDecimal)(request.coinPrice)
        cmds = validators.toList.map(v =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v.toPrim))
            .exerciseCoinRules_MiningRound_Open(price)
            .command
        )
        // For now we submit one command per validator. We could do some batching here but given
        // that this is only a workaround that doesn’t seem worth worrying about.
        submitResults <- cmds.traverse { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield v0.OpenRoundResponse(
        roundResultsToProto(
          decodeRoundResults(CC.Round.OpenMiningRound)(_.obs)(submitResults)
        )
      )
    }

  override def startClosingRound(
      request: v0.StartClosingRoundRequest
  ): Future[v0.StartClosingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        openRounds <- getRounds(CC.Round.OpenMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_StartClosing(cid)
            .command
        }
        submitResults <- cmds.traverse { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield v0.StartClosingRoundResponse(
        roundResultsToProto(
          decodeRoundResults(CC.Round.ClosingMiningRound)(_.obs)(submitResults)
        )
      )
    }

  override def startIssuingRound(
      request: v0.StartIssuingRoundRequest
  ): Future[v0.StartIssuingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        openRounds <- getRounds(CC.Round.ClosingMiningRound)(_.round, _.obs)(svc, request.round)
        rewards <- queryRewards(svc, request.round)
        totalBurn = rewards.totalBurn
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_StartIssuing(cid, totalBurn)
            .command
        }
        submitResults <- cmds.traverse { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield v0.StartIssuingRoundResponse(
        Proto.encode(totalBurn),
        roundResultsToProto(
          decodeRoundResults(CC.Round.IssuingMiningRound)(_.obs)(submitResults)
        ),
      )
    }

  override def closeRound(request: v0.CloseRoundRequest): Future[v0.CloseRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        openRounds <- getRounds(CC.Round.IssuingMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_Close(cid)
            .command
        }
        submitResults <- cmds.traverse { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield v0.CloseRoundResponse(
        roundResultsToProto(
          decodeRoundResults(CC.Round.ClosedMiningRound)(_.obs)(submitResults)
        )
      )
    }

  override def archiveRound(request: v0.ArchiveRoundRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- connection.getPrimaryParty(svcUserName)
        openRounds <- getRounds(CC.Round.ClosedMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_Archive(cid)
            .command
        }
        _ <- cmds.traverse_ { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield Empty()
    }

  // Note that this assumes we do not concurrently onboard validators, otherwise this starts to become racy
  // and we might miss out on validators. Given that this is only a workaround until we have explicit disclosure
  // we accept this for now.
  private def getValidators(svc: PartyId): Future[Set[PartyId]] =
    for {
      coinRules <- connection.activeContracts(svc, CC.CoinRules.CoinRules)
    } yield {
      coinRules.map(c => PartyId.tryFromPrim(c.value.obs)).toSet
    }

  /** Query the ACS for the given template and filter by round. Returns a map from the validator party
    * to the corresponding contract id.
    * Fails if there is more than one round for a given validator.
    */
  private def getRounds[T](companion: TemplateCompanion[T])(
      getRound: T => CC.Round.Round,
      getValidator: T => Primitive.Party,
  )(svc: PartyId, round: Long): Future[Map[Primitive.Party, Primitive.ContractId[T]]] =
    for {
      allRounds <- connection.activeContracts(svc, companion)
    } yield {
      val filteredRounds = allRounds.filter { case roundContract =>
        val roundId = getRound(roundContract.value)
        roundId.number === round
      }
      filteredRounds.groupBy(round => getValidator(round.value)).map { case (v, rs) =>
        require(
          rs.length == 1,
          s"Expected one round for validator $v but got ${rs.length} rounds $rs",
        )
        v -> rs(0).contractId
      }
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
