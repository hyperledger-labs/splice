package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.api.v1.commands.Command
import com.daml.ledger.client.binding
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.codegen.{CC, OpenBusiness}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

object CoinUtil {

  def templateId[T](id: binding.Primitive.TemplateId[T]): TemplateId =
    TemplateId(ApiTypes.TemplateId.unwrap(id))

  /** Creates a contract that gives the given validator the right to claim coin issuances for the given user's burns. */
  def recordValidatorOfCommand(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
  ): Command =
    CC.Coin
      .ValidatorRight(
        svc = svc.toPrim,
        validator = validator.toPrim,
        user = user.toPrim,
      )
      .create
      .command

  lazy val defaultHoldingFee =
    OpenBusiness.Fees.RatePerRound(damlNumeric(1.0 / 360.0 / (24.0 * 60.0 / 2.5)))

  // TODO(M1-90) surely there's a better way to define Daml Numeric values in Scala
  def damlNumeric(x: Double): BigDecimal =
    BigDecimal(x).setScale(10, BigDecimal.RoundingMode.HALF_EVEN)

  def defaultCoinConfig: CC.CoinRules.CoinConfig[CC.CoinRules.USD] = CC.CoinRules.CoinConfig(
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
    // TODO(M1-90) Finetuning required
    transferFee = OpenBusiness.Fees.SteppedRate(
      initialRate = 0.01,
      steps = Seq(
        com.daml.network.codegen.DA.Types.Tuple2(BigDecimal(100.0), BigDecimal(0.001)),
        com.daml.network.codegen.DA.Types.Tuple2(BigDecimal(1000.0), BigDecimal(0.0001)),
        com.daml.network.codegen.DA.Types.Tuple2(BigDecimal(1000000.0), BigDecimal(0.00001)),
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
    // TODO(M1-90): charge per character
    maxPayloadLength = 32,
  )

  // TODO(M1-51): Remove workaround for explicit disclosure
  object ExplicitDisclosureWorkaround {

    /** Like recordUserHostedAt but only builds the command.
      */
    def recordUserHostedAtCommand(
        user: PartyId,
        validator: PartyId,
    ): Command =
      CC.Scripts.Util
        .CCUserHostedAt(
          user = user.toPrim,
          validator = validator.toPrim,
        )
        .create
        .command

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
    )(implicit traceContext: TraceContext): Future[Unit] = {
      connection.ignoreDuplicateKeyErrors(
        connection.submitCommand(
          actAs = Seq(user),
          readAs = Seq.empty,
          command = Seq(recordUserHostedAtCommand(user, validator)),
        ),
        s"CCUserHostedAt($user, $validator)",
      )
    }
  }

  def holdingFee(
      coin: Coin,
      currentRound: Long,
  ): BigDecimal = {
    (currentRound - coin.quantity.createdAt.number) * coin.quantity.ratePerRound.rate
  }

  def currentQuantity(
      coin: Coin,
      currentRound: Long,
  ): BigDecimal = {
    coin.quantity.initialQuantity - holdingFee(coin, currentRound)
  }
}
