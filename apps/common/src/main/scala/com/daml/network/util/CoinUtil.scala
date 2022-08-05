package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.client.binding
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.network.{CC, OpenBusiness}
import com.digitalasset.network.CC.Coin.Coin

import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.client.binding.{Contract, Primitive}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.network.CC.CoinRules.CoinRules

import scala.concurrent.{ExecutionContext, Future}

object CoinUtil extends UploadablePackage {
  lazy val coinTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(Coin.id)

  lazy val packageId: String = coinTemplateId.packageId
  lazy val coinModuleName: String = coinTemplateId.moduleName
  lazy val coinEntityName: String = coinTemplateId.entityName

  // See `Compile / damlCodeGeneration` in build.sbt
  lazy val resourcePath: String = "dar/canton-coin.dar"

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def tryGetCoinRules(connection: CoinLedgerConnection, validator: PartyId)(implicit
      ec: ExecutionContext
  ): Future[Primitive.ContractId[CoinRules]] = {
    for {
      createdRules <- connection.activeContracts(validator, CoinRules)
      _ = require(
        createdRules.length == 1,
        s"Expected only one CoinRules instance but found ${createdRules.size} instances: $createdRules",
      )
      rule = createdRules.head.contractId
    } yield rule
  }

  def templateId[T](id: binding.Primitive.TemplateId[T]): TemplateId =
    TemplateId(ApiTypes.TemplateId.unwrap(id))

  /** Creates a contract that gives the given validator the right to claim coin issuances for the given user's burns. */
  def recordValidatorOf(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
      connection: CoinLedgerConnection,
  )(implicit traceContext: TraceContext): Future[SubmitAndWaitForTransactionResponse] = {
    val c = CC.Coin.ValidatorRight(
      svc = svc.toPrim,
      validator = validator.toPrim,
      user = user.toPrim,
    )
    connection.submitCommand(
      actAs = Seq(validator, user),
      readAs = Seq.empty,
      command = Seq(c.create.command),
    )
  }

  def setupApp(
      svc: PartyId,
      connection: CoinLedgerConnection,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    for {
      // Needed for ED workaround
      _ <- ExplicitDisclosureWorkaround.recordUserHostedAt(svc, svc, connection)
      // The SVC is its own validator
      _ <- recordValidatorOf(svc, svc, svc, connection)

      // Create an IssuanceState
      _ <- connection.submitCommand(
        actAs = Seq(svc),
        readAs = Seq.empty,
        command = Seq(
          CC.Coin
            .IssuanceState(
              svc = svc.toPrim,
              obs = svc.toPrim,
              currentRound = CC.Round.Round(-1),
            )
            .create
            .command
        ),
      )

      // Create CoinRules and open a first mining round
      _ <- connection.submitCommand(
        actAs = Seq(svc),
        readAs = Seq.empty,
        command = Seq(
          CC.CoinRules
            .CoinRules(
              svc = svc.toPrim,
              obs = svc.toPrim,
              config = defaultCoinConfig,
            )
            .createAnd
            .exerciseCoinRules_MiningRound_Open(
              actor = svc.toPrim,
              choiceArgument = CC.CoinRules.CoinRules_MiningRound_Open(
                coinPrice = 1.0
              ),
            )
            .command
        ),
      )
    } yield ()
  }

  def acceptCoinRulesRequests(
      svcPartyId: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    val coinRulesRequestTid = templateId(CC.CoinRules.CoinRulesRequest.id)
    val openMiningRoundTid = templateId(CC.Round.OpenMiningRound.id)
    val issuingMiningRoundTid = templateId(CC.Round.IssuingMiningRound.id)

    for {
      // Note: a single ACS lookup in order to have a consistent view of data
      (requestCids, openMiningRounds, issuingMiningRounds) <- connection
        .activeContracts(
          LedgerConnection.transactionFilterByParty(
            Map(svcPartyId -> Seq(coinRulesRequestTid, openMiningRoundTid, issuingMiningRoundTid))
          )
        )
        .map { case events =>
          val requestCids = events
            .flatMap(DecodeUtil.decodeCreated(CC.CoinRules.CoinRulesRequest))
            .map(_.contractId)
          val openMiningRounds = events
            .flatMap(DecodeUtil.decodeCreated(CC.Round.OpenMiningRound))
            .filter(c => c.value.obs == svcPartyId.toPrim)
            .map(_.value)
          val issuingMiningRounds = events
            .flatMap(DecodeUtil.decodeCreated(CC.Round.IssuingMiningRound))
            .filter(c => c.value.obs == svcPartyId.toPrim)
            .map(_.value)
          (requestCids, openMiningRounds, issuingMiningRounds)
        }
      _ <- Future.sequence(
        requestCids
          .map(cid =>
            connection
              .submitCommand(
                actAs = Seq(svcPartyId),
                readAs = Seq.empty,
                command = Seq(
                  cid
                    .exerciseAccept(svcPartyId.toPrim, openMiningRounds, issuingMiningRounds)
                    .command
                ),
              )
              .recoverWith { case e =>
                logger.warn(s"Failed to accept coin rules request: $e")

                // Note: we are potentially accepting multiple requests, don't fail the whole call if one of them fails.
                // No other workflow is using CoinRulesRequest contracts, it is safe to blindly retry
                // exercising the (consuming) Accept choice until it succeeds.
                Future.successful(())
              }
          )
      )
    } yield ()
  }

  lazy val defaultHoldingFee =
    OpenBusiness.Fees.RatePerRound(damlNumeric(1.0 / 360.0 / (24.0 * 60.0 / 2.5)))

  // TODO(M1-90) surely there's a better way to define Daml Numeric values in Scala
  def damlNumeric(x: Double): BigDecimal =
    BigDecimal(x).setScale(10, BigDecimal.RoundingMode.HALF_EVEN)

  def defaultCoinConfig: CC.CoinRules.CoinConfig = CC.CoinRules.CoinConfig(
    // Fee to create a new coin.
    // Set to the fixed part of the transfer fee.
    createFee = OpenBusiness.Fees.FixedFee(0.09),

    // Fee to update an existing coin.
    // Cost covering and 10x lower than creation to strongly incentivize merging coins.
    updateFee = OpenBusiness.Fees.FixedFee(0.01),

    // Fee for keeping a coin around.
    // This is roughly equivalent to 1$/360 days but expressed as rounds
    // with one day corresponding to 24*60/2.5 rounds, i.e., one round
    // every 2.5 minutes.
    // Incentivizes users to actively merge their coins.
    holdingFee = defaultHoldingFee,

    // The minimal quantity of burn for which a receipt is issued.
    // Chosen to cover to be just above the cost of updating a single update.
    minRewardQuantity = 0.011, // in $

    // Fee for transferring some quantity of coin to a new owner.
    // TODO Finetuning required
    transferFee = OpenBusiness.Fees.SteppedRate(
      initialRate = 0.01,
      steps = Seq(
        com.digitalasset.network.DA.Types.Tuple2(BigDecimal(100.0), BigDecimal(0.001)),
        com.digitalasset.network.DA.Types.Tuple2(BigDecimal(1000.0), BigDecimal(0.0001)),
        com.digitalasset.network.DA.Types.Tuple2(BigDecimal(1000000.0), BigDecimal(0.00001)),
      ),
    ),

    // Coins issued per mining round.
    // Set to 60 coins, which implies a coin price of 1 $ at burn-mint-equilibrium (BME) when the network spends
    // 0.1 $/second in discounted transfer fees and a price of 100$ when the network spends 10 $ per second in discounted fees.
    coinsIssuedPerRound = 60.0,
    svcIssuanceRatio = 0.35,

    // These should be large enough to ensure efficient batching, but not too large
    // to avoid creating very large transactions.
    maxNumInputs = 100,
    maxNumOutputs = 100,

    // Fits a hex-encoded SHA-256 or a UUID
    // TODO: charge per character
    maxPayloadLength = 32,
  )

  // TODO(M1-06): Remove workaround for explicit disclosure
  object ExplicitDisclosureWorkaround {

    /** Records that the given user is hosted at the given validator
      * by creating a CCUserHostedAt contract.
      *
      * Unlike `ValidatorRight` (which is part of the core model and can be added/removed at any time),
      * the `CCUserHostedAt` contract needs to be created immediately after allocating a user,
      * as otherwise the user won't be able to transfer any coins.
      */
    def recordUserHostedAt(
        user: PartyId,
        validator: PartyId,
        connection: CoinLedgerConnection,
    )(implicit traceContext: TraceContext): Future[SubmitAndWaitForTransactionResponse] = {
      val c = CC.Scripts.Util.CCUserHostedAt(
        user = user.toPrim,
        validator = validator.toPrim,
      )
      connection.submitCommand(
        actAs = Seq(user),
        readAs = Seq.empty,
        command = Seq(c.create.command),
      )
    }
  }
}
