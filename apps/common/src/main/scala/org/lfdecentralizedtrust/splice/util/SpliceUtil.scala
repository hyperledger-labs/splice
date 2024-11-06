// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.Unit as DamlUnit
import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.{
  BaseRateTrafficLimits,
  AmuletDecentralizedSynchronizerConfig,
  SynchronizerFeesConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.da.set.types.Set as DamlSet
import org.lfdecentralizedtrust.splice.environment.{
  SpliceLedgerConnection,
  CommandPriority,
  DarResource,
  DarResources,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.math.RoundingMode
import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object SpliceUtil {

  private def readDarVersion(resource: DarResource): PackageVersion =
    DarUtil.readDarMetadata(resource.path).version

  def readPackageConfig(): splice.amuletconfig.PackageConfig = {
    new splice.amuletconfig.PackageConfig(
      readDarVersion(DarResources.amulet.bootstrap).toString,
      readDarVersion(DarResources.amuletNameService.bootstrap).toString,
      readDarVersion(DarResources.dsoGovernance.bootstrap).toString,
      readDarVersion(DarResources.validatorLifecycle.bootstrap).toString,
      readDarVersion(DarResources.wallet.bootstrap).toString,
      readDarVersion(DarResources.walletPayments.bootstrap).toString,
    )
  }

  def selectLatestOpenMiningRound[Ct <: ContractWithState[?, splice.round.OpenMiningRound]](
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

  def selectSpecificOpenMiningRound[Ct <: ContractWithState[?, splice.round.OpenMiningRound]](
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

  /** Creates a contract that gives the given validator the right to claim amulet issuances for the given user's burns. */
  private def createValidatorRightCommand(
      dso: PartyId,
      validator: PartyId,
      user: PartyId,
  ) = new splice.amulet.ValidatorRight(
    dso.toProtoPrimitive,
    user.toProtoPrimitive,
    validator.toProtoPrimitive,
  ).create

  def createValidatorRight(
      dso: PartyId,
      validator: PartyId,
      user: PartyId,
      logger: TracedLogger,
      connection: SpliceLedgerConnection,
      domainId: DomainId,
      retryProvider: RetryProvider,
      lookupValidatorRightByParty: (
          PartyId
      ) => Future[
        QueryResult[
          Option[
            ContractWithState[splice.amulet.ValidatorRight.ContractId, splice.amulet.ValidatorRight]
          ]
        ]
      ],
      priority: CommandPriority = CommandPriority.Low,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    retryProvider.retryForClientCalls(
      "createValidatorRight",
      "createValidatorRight",
      lookupValidatorRightByParty(user).flatMap {
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(validator, user),
              readAs = Seq.empty,
              createValidatorRightCommand(dso, validator, user),
              priority = priority,
            )
            .withDedup(
              commandId = SpliceLedgerConnection
                .CommandId(
                  "org.lfdecentralizedtrust.splice.validator.createValidatorRight",
                  Seq(user),
                ),
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

  val defaultInitialTickDuration = NonNegativeFiniteDuration.ofMinutes(10)

  private val decimalScale = Numeric.Scale.assertFromInt(10)

  private val roundsPerYear: Numeric =
    com.digitalasset.daml.lf.data.assertRight(
      Numeric.divide(
        decimalScale,
        damlNumeric(365.0 * 24 * 60),
        damlNumeric(BigDecimal(defaultInitialTickDuration.duration.toMinutes)),
      )
    )

  lazy val defaultHoldingFee = // ~= 1.9290123456790123E-5 ~= 1.9E-5
    new splice.fees.RatePerRound(
      com.digitalasset.daml.lf.data.assertRight(
        Numeric.divide(
          decimalScale,
          damlNumeric(1.0),
          roundsPerYear,
        )
      )
    )

  // Dedicated helper because Scala doesn't always do automatic downcasting
  def damlDecimal(x: BigDecimal): java.math.BigDecimal =
    damlNumeric(x)

  def damlNumeric(x: BigDecimal): Numeric =
    Numeric.assertFromBigDecimal(decimalScale, x)

  // Using the issuance config for the 10+ years segment of the curve
  def issuanceConfig(
      amuletsToIssuePerYear: Double,
      validatorPercentage: Double,
      appPercentage: Double,
  ): splice.issuance.IssuanceConfig = new IssuanceConfig(
    damlDecimal(amuletsToIssuePerYear),
    damlDecimal(validatorPercentage),
    damlDecimal(appPercentage),

    // validatorRewardCap
    damlDecimal(0.2),

    // featuredAppRewardCap
    damlDecimal(100),

    // unfeaturedAppRewardCap
    damlDecimal(0.6),

    // validatorFaucetCap
    Some(damlDecimal(2.85)).toJava,
  )

  private def hours(h: Long): RelTime = new RelTime(TimeUnit.HOURS.toMicros(h))

  val defaultIssuanceCurve: splice.schedule.Schedule[RelTime, IssuanceConfig] =
    new Schedule(
      issuanceConfig(40e9, 0.05, 0.15),
      Seq(
        new Tuple2(hours(365 * 12), issuanceConfig(20e9, 0.12, 0.4)),
        new Tuple2(hours(3 * 365 * 12), issuanceConfig(10e9, 0.18, 0.62)),
        new Tuple2(hours(5 * 365 * 24), issuanceConfig(5e9, 0.21, 0.69)),
        new Tuple2(hours(10 * 365 * 24), issuanceConfig(2.5e9, 0.20, 0.75)),
      ).asJava,
    )

  val defaultCreateFee = new splice.fees.FixedFee(damlDecimal(0.03))

  val defaultTransferFee = new splice.fees.SteppedRate(
    damlDecimal(0.01),
    Seq(
      new Tuple2(damlDecimal(100.0), damlDecimal(0.001)),
      new Tuple2(damlDecimal(1000.0), damlDecimal(0.0001)),
      new Tuple2(damlDecimal(1000000.0), damlDecimal(0.00001)),
    ).asJava,
  )

  val defaultLockHolderFee = new splice.fees.FixedFee(damlDecimal(0.005))

  // These are dummy values only made use of by some unit tests.
  // The synchronizer fees parameters are provided in sv1 App config with the defaults in SynchronizerFeesConfig
  private val dummyExtraTrafficPrice = BigDecimal(1.0) // extraTrafficPrice (in $/MB)
  private val dummyMinTopupAmount = 1_000_000L
  private val dummyBaseRateBurstAmount = 10 * 20 * 1000L
  private val dummyBaseRateBurstWindow = NonNegativeFiniteDuration.ofMinutes(10)
  private val dummyReadVsWriteScalingFactor = 4

  // TODO(tech-debt) revisit naming here. "default" and "initial" are two things that are no longer accurate (these are used for other things as well), and consider adding more default values to methods here

  def defaultAmuletConfigSchedule(
      initialTickDuration: NonNegativeFiniteDuration,
      initialMaxNumInputs: Int,
      initialDomainId: DomainId,
      initialExtraTrafficPrice: BigDecimal = dummyExtraTrafficPrice,
      initialMinTopupAmount: Long = dummyMinTopupAmount,
      initialBaseRateBurstAmount: Long = dummyBaseRateBurstAmount,
      initialBaseRateBurstWindow: NonNegativeFiniteDuration = dummyBaseRateBurstWindow,
      initialReadVsWriteScalingFactor: Int = dummyReadVsWriteScalingFactor,
      initialPackageConfig: splice.amuletconfig.PackageConfig = readPackageConfig(),
      holdingFee: BigDecimal = defaultHoldingFee.rate,
      transferPreapprovalFee: Option[BigDecimal] = None,
  ) = new splice.schedule.Schedule[Instant, splice.amuletconfig.AmuletConfig[
    splice.amuletconfig.USD
  ]](
    defaultAmuletConfig(
      initialTickDuration,
      initialMaxNumInputs,
      initialDomainId,
      initialExtraTrafficPrice,
      initialMinTopupAmount,
      initialBaseRateBurstAmount,
      initialBaseRateBurstWindow,
      initialReadVsWriteScalingFactor,
      initialPackageConfig,
      holdingFee,
      transferPreapprovalFee,
    ),
    List.empty[Tuple2[Instant, splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD]]].asJava,
  )

  // Fee for keeping a transfer-preapproval around.
  // Similar to holding fees, it compensates the SVs for the storage cost of the contract.
  // Roughly equal to $1/year expressed as a daily rate.
  lazy val defaultTransferPreapprovalFee = damlDecimal(0.00274)

  def defaultAmuletConfig(
      initialTickDuration: NonNegativeFiniteDuration,
      initialMaxNumInputs: Int,
      initialDomainId: DomainId,
      initialExtraTrafficPrice: BigDecimal = dummyExtraTrafficPrice,
      initialMinTopupAmount: Long = dummyMinTopupAmount,
      initialBaseRateBurstAmount: Long = dummyBaseRateBurstAmount,
      initialBaseRateBurstWindow: NonNegativeFiniteDuration = dummyBaseRateBurstWindow,
      initialReadVsWriteScalingFactor: Int = dummyReadVsWriteScalingFactor,
      initialPackageConfig: splice.amuletconfig.PackageConfig = readPackageConfig(),
      holdingFee: BigDecimal = defaultHoldingFee.rate,
      transferPreapprovalFee: Option[BigDecimal] = None,
      nextDomainId: Option[DomainId] = None,
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] =
    new splice.amuletconfig.AmuletConfig(
      // transferConfig
      defaultTransferConfig(initialMaxNumInputs, holdingFee),

      // issuance curve
      defaultIssuanceCurve,

      // global domain config
      defaultDecentralizedSynchronizerConfig(
        initialDomainId,
        nextDomainId,
        initialExtraTrafficPrice,
        initialMinTopupAmount,
        initialBaseRateBurstAmount,
        initialBaseRateBurstWindow,
        initialReadVsWriteScalingFactor,
      ),

      // tick duration
      new RelTime(TimeUnit.NANOSECONDS.toMicros(initialTickDuration.duration.toNanos)),
      initialPackageConfig,
      transferPreapprovalFee.map(_.bigDecimal).toJava,
    )

  def defaultAnsConfig(
      renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
      entryLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
      entryFee: Double = 1.0,
  ): splice.ans.AnsRulesConfig = new splice.ans.AnsRulesConfig(
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

    // Description prefix
    "ANS entry: ",
  )

  private def defaultDecentralizedSynchronizerConfig(
      initialDomainId: DomainId,
      nextDomainId: Option[DomainId],
      initialExtraTrafficPrice: BigDecimal,
      initialMinTopupAmount: Long,
      initialBaseRateBurstAmount: Long,
      initialBaseRateBurstWindow: NonNegativeFiniteDuration,
      initialReadVsWriteScalingFactor: Int,
  ): AmuletDecentralizedSynchronizerConfig = {
    val domainId = initialDomainId.toProtoPrimitive
    val next = nextDomainId.map(_.toProtoPrimitive)
    new AmuletDecentralizedSynchronizerConfig(
      // requiredSynchronizers
      new DamlSet(
        (Map(domainId -> DamlUnit.getInstance) ++ next
          .map(_ -> DamlUnit.getInstance)
          .toList).asJava
      ),
      // activeSynchronizer
      next getOrElse domainId,
      // fees
      domainFeesConfig(
        baseRateBurstAmount = initialBaseRateBurstAmount,
        baseRateBurstWindow = initialBaseRateBurstWindow,
        readVsWriteScalingFactor = initialReadVsWriteScalingFactor,
        extraTrafficPrice = initialExtraTrafficPrice,
        minTopupAmount = initialMinTopupAmount,
      ),
    )
  }

  def defaultTransferConfig(
      initialMaxNumInputs: Int,
      holdingFee: BigDecimal,
  ): splice.amuletconfig.TransferConfig[splice.amuletconfig.USD] =
    new splice.amuletconfig.TransferConfig(
      // Fee to create a new amulet.
      // Set to the fixed part of the transfer fee.
      defaultCreateFee,

      // Fee for keeping a amulet around.
      // This is roughly equivalent to 1$/360 days but expressed as rounds
      // with one day corresponding to 24*60/2.5 rounds, i.e., one round
      // every 2.5 minutes.
      // Incentivizes users to actively merge their amulets.
      new splice.fees.RatePerRound(
        holdingFee.bigDecimal.setScale(10, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal
      ),

      // Fee for transferring some amount of amulet to a new owner.
      defaultTransferFee,

      // Fee per lock holder.
      // Chosen to match the update fee to cover the cost of informing lock-holders about
      // actions on the locked amulet.
      defaultLockHolderFee,

      // Extra featured app reward amount, chosen to be equal to the domain fee cost of a single CC transfer
      damlDecimal(1.0),

      // These should be large enough to ensure efficient batching, but not too large
      // to avoid creating very large transactions.
      initialMaxNumInputs.toLong,
      100,

      // Maximum number of lock holders.
      // Chosen conservatively, but high enough to invite thinking about what's possible.
      50,
      // 2.5 min default duration
    )

  def baseRateLimits(baseRateBurstAmount: Long, baseRateBurstWindow: NonNegativeFiniteDuration) = {
    new BaseRateTrafficLimits(
      baseRateBurstAmount,
      new RelTime(TimeUnit.NANOSECONDS.toMicros(baseRateBurstWindow.duration.toNanos)),
    )
  }

  private def domainFeesConfig(
      baseRateBurstAmount: Long,
      baseRateBurstWindow: NonNegativeFiniteDuration,
      readVsWriteScalingFactor: Int,
      extraTrafficPrice: BigDecimal,
      minTopupAmount: Long,
  ) = {
    new SynchronizerFeesConfig(
      baseRateLimits(baseRateBurstAmount, baseRateBurstWindow),
      damlDecimal(extraTrafficPrice.toDouble),
      readVsWriteScalingFactor,
      minTopupAmount,
    )
  }

  def holdingFee(
      amulet: Amulet,
      currentRound: Long,
  ): java.math.BigDecimal = {
    amulet.amount.initialAmount.min(
      java.math.BigDecimal
        .valueOf(currentRound - amulet.amount.createdAt.number)
        .setScale(10)
        .multiply(amulet.amount.ratePerRound.rate)
        .setScale(10, RoundingMode.HALF_EVEN)
    )
  }

  def currentAmount(
      amulet: Amulet,
      currentRound: Long,
  ): java.math.BigDecimal = {
    amulet.amount.initialAmount.subtract(holdingFee(amulet, currentRound))
  }

  def amuletExpiresAt(amulet: Amulet): Round = {
    val rounds = amulet.amount.initialAmount
      .divide(
        amulet.amount.ratePerRound.rate,
        0,
        RoundingMode.CEILING,
      )
    try {
      val roundsLong = rounds.longValueExact
      new Round(amulet.amount.createdAt.number + roundsLong)
    } catch {
      case _: ArithmeticException =>
        new Round(Long.MaxValue)
    }
  }

  def relTimeToDuration(dt: RelTime): Duration =
    Duration.ofNanos(dt.microseconds * 1000)

  /** Converts the given amount of USD to an amount of CC, at the given amulet price.
    * Uses the same semantics for numerical division as Daml.
    */
  def dollarsToCC(
      usd: java.math.BigDecimal,
      amuletPrice: java.math.BigDecimal,
  ): java.math.BigDecimal = {
    val usdN = damlNumeric(usd)
    val amuletPriceN = damlNumeric(amuletPrice)
    com.digitalasset.daml.lf.data.assertRight(Numeric.divide(decimalScale, usdN, amuletPriceN))
  }

  def ccToDollars(
      cc: java.math.BigDecimal,
      amuletPrice: java.math.BigDecimal,
  ): java.math.BigDecimal = {
    val ccN = damlNumeric(cc)
    val amuletPriceN = damlNumeric(amuletPrice)
    com.digitalasset.daml.lf.data.assertRight(Numeric.multiply(decimalScale, amuletPriceN, ccN))
  }

  def synchronizerFees(
      topupAmount: Long,
      extraTrafficPrice: BigDecimal,
      amuletPrice: BigDecimal,
  ): (BigDecimal, BigDecimal) = {

    def tryCompute() = for {
      extraTrafficPriceN <- Numeric.fromBigDecimal(decimalScale, extraTrafficPrice)
      amuletPriceN <- Numeric.fromBigDecimal(decimalScale, amuletPrice)
      topupAmountN <- Numeric.fromLong(decimalScale, topupAmount)
      bytesInMB <- Numeric.fromLong(decimalScale, 1_000_000L)
      topupAmountMB <- Numeric.divide(decimalScale, topupAmountN, bytesInMB)
      trafficCostUsd <- Numeric.multiply(decimalScale, extraTrafficPriceN, topupAmountMB)
      trafficCostAmulet <- Numeric.divide(decimalScale, trafficCostUsd, amuletPriceN)
    } yield (BigDecimal(trafficCostUsd), BigDecimal(trafficCostAmulet))

    com.digitalasset.daml.lf.data.assertRight(tryCompute())
  }

  def transferPreapprovalFees(
      duration: NonNegativeFiniteDuration,
      preapprovalFeeRate: Option[BigDecimal],
      amuletPrice: BigDecimal,
  ): (BigDecimal, BigDecimal) = {

    def tryCompute() = for {
      preapprovalFeeN <- Numeric.fromBigDecimal(
        decimalScale,
        preapprovalFeeRate.getOrElse(BigDecimal(defaultTransferPreapprovalFee)),
      )
      amuletPriceN <- Numeric.fromBigDecimal(decimalScale, amuletPrice)
      durationDays = BigDecimal(duration.duration.toSeconds) / (3600 * 24)
      durationDaysN <- Numeric.fromBigDecimal(decimalScale, durationDays)
      feeUsd <- Numeric.multiply(decimalScale, preapprovalFeeN, durationDaysN)
      feeAmulet <- Numeric.divide(decimalScale, feeUsd, amuletPriceN)
    } yield (BigDecimal(feeUsd), BigDecimal(feeAmulet))

    com.digitalasset.daml.lf.data.assertRight(tryCompute())
  }
}
