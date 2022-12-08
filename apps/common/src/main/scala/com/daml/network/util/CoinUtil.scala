package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.client.binding
import com.daml.ledger.javaapi.data.Command
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

object CoinUtil {

  def templateId[T](id: binding.Primitive.TemplateId[T]): TemplateId =
    TemplateId(ApiTypes.TemplateId.unwrap(id))

  /** Creates a contract that gives the given validator the right to claim coin issuances for the given user's burns. */
  private def createValidatorRightCommand(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
  ): Seq[Command] =
    new cc.coin.ValidatorRight(
      svc.toProtoPrimitive,
      user.toProtoPrimitive,
      validator.toProtoPrimitive,
    ).create.commands.asScala.toSeq

  def createValidatorRight(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
      logger: TracedLogger,
      connection: CoinLedgerConnection,
      retryProvider: CoinRetries,
      flagCloseable: FlagCloseable,
      lookupValidatorRightByParty: (
          PartyId
      ) => Future[
        QueryResult[Option[JavaContract[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]]
      ],
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    retryProvider.retryForAutomation(
      "createValidatorRight",
      lookupValidatorRightByParty(user).flatMap {
        case QueryResult(off, None) =>
          connection
            .submitCommands(
              actAs = Seq(validator, user),
              readAs = Seq.empty,
              commands = createValidatorRightCommand(svc, validator, user),
              commandId = CoinLedgerConnection
                .CommandId("com.daml.network.validator.createValidatorRight", Seq(user)),
              deduplicationOffset = off,
            )
            .map(_ => ())
        case QueryResult(_, Some(_)) =>
          logger.info(s"ValidatorRight for $user already exists, skipping")
          Future.successful(())
      },
      flagCloseable,
    )

  lazy val defaultHoldingFee =
    new cc.fees.RatePerRound(damlNumeric(1.0 / 360.0 / (24.0 * 60.0 / 2.5)))

  // TODO(M1-90) surely there's a better way to define Daml Numeric values in Scala
  def damlNumeric(x: Double): java.math.BigDecimal =
    BigDecimal(x).setScale(10, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal

  def defaultCoinConfig(
      initialTickDuration: NonNegativeFiniteDuration
  ): cc.coin.CoinConfig[cc.coin.USD] = new cc.coin.CoinConfig(
    // Fee to create a new coin.
    // Set to the fixed part of the transfer fee.
    new cc.fees.FixedFee(BigDecimal(0.09).bigDecimal),

    // Fee to update an existing coin.
    // Cost covering and 10x lower than creation to strongly incentivize merging coins.
    new cc.fees.FixedFee(BigDecimal(0.01).bigDecimal),

    // Fee for keeping a coin around.
    // This is roughly equivalent to 1$/360 days but expressed as rounds
    // with one day corresponding to 24*60/2.5 rounds, i.e., one round
    // every 2.5 minutes.
    // Incentivizes users to actively merge their coins.
    defaultHoldingFee,

    // Fee for transferring some quantity of coin to a new owner.
    // TODO(M1-90) Finetuning required
    new cc.fees.SteppedRate(
      BigDecimal(0.01).bigDecimal,
      Seq(
        new Tuple2(BigDecimal(100.0).bigDecimal, BigDecimal(0.001).bigDecimal),
        new Tuple2(BigDecimal(1000.0).bigDecimal, BigDecimal(0.0001).bigDecimal),
        new Tuple2(BigDecimal(1000000.0).bigDecimal, BigDecimal(0.00001).bigDecimal),
      ).asJava,
    ),

    // Fee per lock holder.
    // Chosen to match the update fee to cover the cost of informing lock-holders about
    // actions on the locked coin.
    new cc.fees.FixedFee(BigDecimal(0.01).bigDecimal),

    // Coins issued per mining round.
    // Set to 60 coins, which implies a coin price of 1 $ at burn-mint-equilibrium (BME) when the network spends
    // 0.1 $/second in discounted transfer fees and a price of 100$ when the network spends 10 $ per second in discounted fees.
    BigDecimal(60.0).bigDecimal,

    // The minimal quantity of burn for which a receipt is issued.
    // Chosen to cover to be just above the cost of updating a single update.
    BigDecimal(0.011).bigDecimal, // in $

    BigDecimal(0.35).bigDecimal,

    // These should be large enough to ensure efficient batching, but not too large
    // to avoid creating very large transactions.
    100,
    100,

    // Maximum number of lock holders.
    // Chosen conservatively, but high enough to invite thinking about what's possible.
    50,

    // Fits a hex-encoded SHA-256 or a UUID
    // TODO(M1-90): charge per character
    32,
    // 2.5 min default duration
    new RelTime(TimeUnit.NANOSECONDS.toMicros(initialTickDuration.duration.toNanos)),
    new cc.api.v1.coin.EnabledChoices(
      true, true, true, true, true, true, true, true, true, true,
    ),
  )

  def holdingFee(
      coin: Coin,
      currentRound: Long,
  ): java.math.BigDecimal = {
    java.math.BigDecimal
      .valueOf(currentRound - coin.quantity.createdAt.number)
      .multiply(coin.quantity.ratePerRound.rate)
  }

  def currentQuantity(
      coin: Coin,
      currentRound: Long,
  ): java.math.BigDecimal = {
    coin.quantity.initialQuantity.subtract(holdingFee(coin, currentRound))
  }
}
