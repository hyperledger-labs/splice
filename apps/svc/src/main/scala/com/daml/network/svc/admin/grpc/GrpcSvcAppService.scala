package com.daml.network.svc.admin.grpc

import cats.implicits._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.admin.SvcAutomationService
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcAppServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.{CC, DA}
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

  // TODO(M1-90): This should not run concurrently with round management operations.
  // Both are non-atomic read-modify-write operations on the set of mining rounds.
  override def acceptValidators(request: Empty): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svcPartyId <- getParty()
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

  override def openRound(request: v0.OpenRoundRequest): Future[v0.OpenRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- getParty()
        validators <- getValidators(svc)
        price = BigDecimal(request.coinPrice)
        cmds = validators.toList.map(v =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v.toPrim))
            .exerciseCoinRules_MiningRound_Open(
              svc.toPrim,
              CC.CoinRules.CoinRules_MiningRound_Open(
                price
              ),
            )
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
        svc <- getParty()
        openRounds <- getRounds(CC.Round.OpenMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_StartClosing(
              svc.toPrim,
              CC.CoinRules.CoinRules_MiningRound_StartClosing(cid),
            )
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
        svc <- getParty()
        openRounds <- getRounds(CC.Round.ClosingMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_StartIssuing(
              svc.toPrim,
              CC.CoinRules
                .CoinRules_MiningRound_StartIssuing(cid, BigDecimal(request.totalBurnQuantity)),
            )
            .command
        }
        submitResults <- cmds.traverse { cmd =>
          connection.submitCommand(Seq(svc), Seq.empty, Seq(cmd))
        }
      } yield v0.StartIssuingRoundResponse(
        roundResultsToProto(
          decodeRoundResults(CC.Round.IssuingMiningRound)(_.obs)(submitResults)
        )
      )
    }

  override def closeRound(request: v0.CloseRoundRequest): Future[v0.CloseRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        svc <- getParty()
        openRounds <- getRounds(CC.Round.IssuingMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_Close(
              svc.toPrim,
              CC.CoinRules.CoinRules_MiningRound_Close(cid),
            )
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
        svc <- getParty()
        openRounds <- getRounds(CC.Round.ClosedMiningRound)(_.round, _.obs)(svc, request.round)
        cmds = openRounds.toList.map { case (v, cid) =>
          CC.CoinRules.CoinRules
            .key(DA.Types.Tuple2(svc.toPrim, v))
            .exerciseCoinRules_MiningRound_Archive(
              svc.toPrim,
              CC.CoinRules.CoinRules_MiningRound_Archive(cid),
            )
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
      activeContracts <- connection.activeContracts(
        CoinLedgerConnection.transactionFilter(svc, CC.CoinRules.CoinRules.id)
      )
      coinRules = activeContracts._1.flatMap(event =>
        DecodeUtil.decodeCreated(CC.CoinRules.CoinRules)(event)
      )
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
      activeContracts <- connection.activeContracts(
        CoinLedgerConnection.transactionFilter(svc, companion.id)
      )
    } yield {
      val allRounds =
        activeContracts._1.flatMap(event => DecodeUtil.decodeCreated(companion)(event))
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

  private def getParty() =
    for {
      partyO <- connection.getUser(svcUserName)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $svcUserName")
      )
    } yield party

  private def roundResultsToProto[T](
      m: Map[Primitive.Party, Primitive.ContractId[T]]
  ): Map[String, String] =
    m.map { case (v, cid) => Primitive.Party.unwrap(v) -> ApiTypes.ContractId.unwrap(cid) }

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
}
