package com.daml.network.util

import cats.syntax.either.*
import com.daml.ledger.javaapi.data.Unit as DamlUnit
import com.daml.lf.archive.DarDecoder
import com.daml.lf.data.Numeric
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.codegen.java.cc.globaldomain.{
  BaseRateTrafficLimits,
  DomainFeesConfig,
  GlobalDomainConfig,
}
import com.daml.network.codegen.java.cc.issuance.IssuanceConfig
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.codegen.java.da.set.types.{Set as DamlSet}
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.io.InputStream
import java.math.RoundingMode
import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object CNNodeUtil {

  private def readDarVersion(resourcePath: String): String = {
    val resourceStream = getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (resourceStream == null) {
      throw new IllegalArgumentException(s"Failed to parse resource: $resourcePath")
    }
    readDarVersion(resourcePath, resourceStream)
  }

  private def readDarVersion(name: String, stream: InputStream): String = {
    Using.resource(new ZipInputStream(stream)) { zipStream =>
      val dar = DarDecoder
        .readArchive(name, zipStream)
        .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
      val metadata = dar.main._2.metadata.getOrElse(
        throw new AssertionError(s"Package is missing metadata which is mandatory in LF >= 1.8")
      )
      metadata.version
    }
  }

  private def readPackageConfig(): cc.coinconfig.PackageConfig = {
    new cc.coinconfig.PackageConfig(
      readDarVersion("dar/canton-coin-0.1.0.dar"),
      readDarVersion("dar/canton-name-service-0.1.0.dar"),
      readDarVersion("dar/directory-service-0.1.0.dar"),
      readDarVersion("dar/svc-governance-0.1.0.dar"),
      readDarVersion("dar/validator-lifecycle-0.1.0.dar"),
      readDarVersion("dar/wallet-0.1.0.dar"),
      readDarVersion("dar/wallet-payments-0.1.0.dar"),
    )
  }

  def selectLatestOpenMiningRound[Ct <: ContractWithState[?, cc.round.OpenMiningRound]](
      now: CantonTimestamp,
      openMiningRounds: Seq[Ct],
  ): Ct = {
    import math.Ordering.Implicits.*
    openMiningRounds.view
      .filter(c => c.payload.opensAt <= now.toInstant)
      .maxByOption(c => c.payload.round.number)
      .getOrElse(
        throw new IllegalStateException(
          s"tried to select the latest open mining round from $openMiningRounds but none of the rounds are open. "
        )
      )
  }

  def selectSpecificOpenMiningRound[Ct <: ContractWithState[?, cc.round.OpenMiningRound]](
      now: CantonTimestamp,
      openMiningRounds: Seq[Ct],
      specifiedRound: Round,
  ): Ct = {
    import math.Ordering.Implicits.*
    openMiningRounds.view
      .filter(c => c.payload.opensAt <= now.toInstant)
      .find(_.payload.round == specifiedRound)
      .getOrElse(
        throw new IllegalStateException(
          s"tried to select the specific open mining round $specifiedRound from $openMiningRounds but none of the rounds match the specified round. "
        )
      )
  }

  /** Creates a contract that gives the given validator the right to claim coin issuances for the given user's burns. */
  private def createValidatorRightCommand(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
  ) = new cc.coin.ValidatorRight(
    svc.toProtoPrimitive,
    user.toProtoPrimitive,
    validator.toProtoPrimitive,
  ).create

  def createValidatorRight(
      svc: PartyId,
      validator: PartyId,
      user: PartyId,
      logger: TracedLogger,
      connection: CNLedgerConnection,
      domainId: DomainId,
      retryProvider: RetryProvider,
      lookupValidatorRightByParty: (
          PartyId
      ) => Future[
        QueryResult[
          Option[ContractWithState[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]
        ]
      ],
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    retryProvider.retryForClientCalls(
      "createValidatorRight",
      lookupValidatorRightByParty(user).flatMap {
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(validator, user),
              readAs = Seq.empty,
              createValidatorRightCommand(svc, validator, user),
            )
            .withDedup(
              commandId = CNLedgerConnection
                .CommandId("com.daml.network.validator.createValidatorRight", Seq(user)),
              deduplicationOffset = offset,
            )
            .withDomainId(domainId)
            .yieldUnit()
        case QueryResult(_, Some(_)) =>
          logger.info(s"ValidatorRight for $user already exists, skipping")
          Future.unit
      },
      logger,
    )

  lazy val defaultHoldingFee = // ~= 4.822530864197531E-6 ~= 4.8E-6
    new cc.fees.RatePerRound(damlDecimal(1.0 / 360.0 / (24.0 * 60.0 / 2.5)))

  // TODO (#6285) surely there's a better way to define Daml Numeric values in Scala
  def damlDecimal(x: Double): java.math.BigDecimal =
    BigDecimal(x).setScale(10, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal

  // Using the issuance config for the 10+ years segment of the curve
  def issuanceConfig(
      coinsToIssuePerYear: Double,
      validatorPercentage: Double,
      appPercentage: Double,
  ): cc.issuance.IssuanceConfig = new IssuanceConfig(
    damlDecimal(coinsToIssuePerYear),
    damlDecimal(validatorPercentage),
    damlDecimal(appPercentage),

    // validatorRewardCap
    damlDecimal(0.2),

    // featuredAppRewardCap
    damlDecimal(100),

    // unfeaturedAppRewardCap
    damlDecimal(0.6),
  )

  private def hours(h: Long): RelTime = new RelTime(TimeUnit.HOURS.toMicros(h))

  // Curve taken as-is from whitepaper: https://docs.google.com/document/d/1SmC0TBcLBqsHgRDBfxbjIbFigPWXfBEW7B9MZpyCxK4/edit#bookmark=id.75er6skh0ext
  val defaultIssuanceCurve: cc.schedule.Schedule[RelTime, IssuanceConfig] =
    new Schedule(
      issuanceConfig(40e9, 0.5, 0.15),
      Seq(
        new Tuple2(hours(365 * 12), issuanceConfig(20e9, 0.12, 0.4)),
        new Tuple2(hours(3 * 365 * 12), issuanceConfig(10e9, 0.18, 0.62)),
        new Tuple2(hours(5 * 365 * 24), issuanceConfig(5e9, 0.21, 0.69)),
        new Tuple2(hours(10 * 365 * 24), issuanceConfig(2.5e9, 0.20, 0.75)),
      ).asJava,
    )

  val defaultCreateFee = new cc.fees.FixedFee(damlDecimal(0.03))

  val defaultTransferFee = new cc.fees.SteppedRate(
    damlDecimal(0.01),
    Seq(
      new Tuple2(damlDecimal(100.0), damlDecimal(0.001)),
      new Tuple2(damlDecimal(1000.0), damlDecimal(0.0001)),
      new Tuple2(damlDecimal(1000000.0), damlDecimal(0.00001)),
    ).asJava,
  )

  val defaultLockHolderFee = new cc.fees.FixedFee(damlDecimal(0.005))

  val defaultExtraTrafficPrice = damlDecimal(1.0) // extraTrafficPrice (in $/MB)
  val defaultReadScalingFactor = damlDecimal(0.02) // charge 2% of write cost for every read
  val defaultDomainFeesConfig = domainFeesConfig(
    // Please keep these values in sync with
    //   - domain-fees-overrides.conf
    //   - domainFeesCfg.ts
    // TODO(#6322): configure these values in at most one place
    damlDecimal(3333.0),
    NonNegativeFiniteDuration.ofMinutes(10),
    // TODO(#6032): determine the best defaults here
    1_000, // minTopupAmount = 1KB
  )

  // TODO(tech-debt) revisit naming here. "default" and "initial" are two things that are no longer accurate (these are used for other things as well), and consider adding more default values to methods here

  def defaultCoinConfigSchedule(
      initialTickDuration: NonNegativeFiniteDuration,
      initialMaxNumInputs: Int,
      initialDomainId: DomainId,
      holdingFee: BigDecimal = defaultHoldingFee.rate,
  ) = new cc.schedule.Schedule[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]](
    defaultCoinConfig(initialTickDuration, initialMaxNumInputs, initialDomainId, holdingFee),
    List.empty[Tuple2[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]]].asJava,
  )

  def defaultCoinConfig(
      initialTickDuration: NonNegativeFiniteDuration,
      initialMaxNumInputs: Int,
      initialDomainId: DomainId,
      holdingFee: BigDecimal = defaultHoldingFee.rate,
      nextDomainId: Option[DomainId] = None,
  ): cc.coinconfig.CoinConfig[cc.coinconfig.USD] = new cc.coinconfig.CoinConfig(
    // transferConfig
    defaultTransferConfig(initialMaxNumInputs, holdingFee),

    // issuance curve from whitepaper
    defaultIssuanceCurve,

    // domainFeesConfig
    defaultGlobalDomainConfig(initialDomainId, nextDomainId),

    // tick duration
    new RelTime(TimeUnit.NANOSECONDS.toMicros(initialTickDuration.duration.toNanos)),
    readPackageConfig(),
  )

  def defaultCnsConfig(
      renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
      entryLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
      entryFee: Double = 1.0,
  ): cn.cns.CnsRulesConfig = new cn.cns.CnsRulesConfig(
    // renewalDuration
    new RelTime(
      TimeUnit.NANOSECONDS.toMicros(renewalDuration.duration.toNanos)
    ),

    // entryLifetime
    new RelTime(
      TimeUnit.NANOSECONDS.toMicros(entryLifetime.duration.toNanos)
    ),

    // entryFee
    damlDecimal(entryFee),
  )

  private def defaultGlobalDomainConfig(
      initialDomainId: DomainId,
      nextDomainId: Option[DomainId],
  ): GlobalDomainConfig = {
    val domainId = initialDomainId.toProtoPrimitive
    val next = nextDomainId.map(_.toProtoPrimitive)
    new GlobalDomainConfig(
      // requiredDomains
      new DamlSet(
        (Map(domainId -> DamlUnit.getInstance) ++ next
          .map(_ -> DamlUnit.getInstance)
          .toList).asJava
      ),
      // activeDomain
      next getOrElse domainId,
      // fees
      defaultDomainFeesConfig,
    )
  }

  def defaultTransferConfig(
      initialMaxNumInputs: Int,
      holdingFee: BigDecimal,
  ): cc.coinconfig.TransferConfig[cc.coinconfig.USD] = new cc.coinconfig.TransferConfig(
    // Fee to create a new coin.
    // Set to the fixed part of the transfer fee.
    defaultCreateFee,

    // Fee for keeping a coin around.
    // This is roughly equivalent to 1$/360 days but expressed as rounds
    // with one day corresponding to 24*60/2.5 rounds, i.e., one round
    // every 2.5 minutes.
    // Incentivizes users to actively merge their coins.
    new cc.fees.RatePerRound(
      holdingFee.bigDecimal.setScale(10, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal
    ),

    // Fee for transferring some amount of coin to a new owner.
    defaultTransferFee,

    // Fee per lock holder.
    // Chosen to match the update fee to cover the cost of informing lock-holders about
    // actions on the locked coin.
    defaultLockHolderFee,

    // These should be large enough to ensure efficient batching, but not too large
    // to avoid creating very large transactions.
    initialMaxNumInputs.toLong,
    100,

    // Maximum number of lock holders.
    // Chosen conservatively, but high enough to invite thinking about what's possible.
    50,
    // 2.5 min default duration
  )

  def baseRateLimits(baseRate: BigDecimal, baseRateBurstWindow: NonNegativeFiniteDuration) = {
    new BaseRateTrafficLimits(
      baseRate.bigDecimal,
      new RelTime(TimeUnit.NANOSECONDS.toMicros(baseRateBurstWindow.duration.toNanos)),
    )
  }

  def domainFeesConfig(
      baseRate: BigDecimal,
      baseRateBurstWindow: NonNegativeFiniteDuration,
      minTopupAmount: Long,
      extraTrafficPrice: BigDecimal = defaultExtraTrafficPrice,
      readScalingFactor: BigDecimal = defaultReadScalingFactor,
  ) = {
    new DomainFeesConfig(
      baseRateLimits(baseRate, baseRateBurstWindow),
      extraTrafficPrice.bigDecimal,
      readScalingFactor.bigDecimal,
      minTopupAmount,
    )
  }

  def holdingFee(
      coin: Coin,
      currentRound: Long,
  ): java.math.BigDecimal = {
    java.math.BigDecimal
      .valueOf(currentRound - coin.amount.createdAt.number)
      .multiply(coin.amount.ratePerRound.rate)
  }

  def currentAmount(
      coin: Coin,
      currentRound: Long,
  ): java.math.BigDecimal = {
    coin.amount.initialAmount.subtract(holdingFee(coin, currentRound))
  }

  def coinExpiresAt(coin: Coin): Round = {
    val rounds = coin.amount.initialAmount
      .divide(
        coin.amount.ratePerRound.rate,
        0,
        RoundingMode.CEILING,
      )
      .longValueExact
    new Round(coin.amount.createdAt.number + rounds)
  }

  def relTimeToDuration(dt: RelTime): Duration =
    Duration.ofNanos(dt.microseconds * 1000)

  /** Converts the given amount of USD to an amount of CC, at the given coin price.
    * Uses the same semantics for numerical division as Daml.
    */
  def dollarsToCC(
      usd: java.math.BigDecimal,
      coinPrice: java.math.BigDecimal,
  ): java.math.BigDecimal = {
    val scale = Numeric.Scale.assertFromInt(10)
    val usdN = Numeric.assertFromBigDecimal(scale, usd)
    val coinPriceN = Numeric.assertFromBigDecimal(scale, coinPrice)
    com.daml.lf.data.assertRight(Numeric.divide(scale, usdN, coinPriceN))
  }
}
