package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.codegen.java.da.types as daTypes
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.{BaseRateTrafficLimits, ValidatorTraffic}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.util.{DisclosedContracts, DomainFeesConstants, TimeTestUtil, WalletTestUtil}
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.scalatest.Assertion
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Span}
import org.slf4j.event.Level

import java.time.Duration
import scala.concurrent.Future
import scala.math.BigDecimal.RoundingMode
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class XNodeDomainFeesTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomainWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withHttpSettingsForHigherThroughput
      // TODO(#5372): remove this or add an explicit test for domain fees on a decentralized domain
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllValidatorConfigs { case (name, validatorConfig) =>
          val domainFeesEnabledConfig = validatorConfig
            .focus(_.treasury.enableValidatorTrafficBalanceChecks)
            .replace(true)
            .focus(_.automation.enableAutomaticValidatorTrafficBalanceTopup)
            .replace(true)
            // set target throughput to 0 for all validators except
            // those explicity specified below
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
          if (name.contains("alice"))
            domainFeesEnabledConfig
              // reduced values to make the test run faster
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(10_000)))
              .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
              .replace(NonNegativeFiniteDuration.ofMinutes(5))
          else if (name.contains("sv1"))
            domainFeesEnabledConfig
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(5_000)))
              .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
              .replace(NonNegativeFiniteDuration.ofMinutes(5))
          else domainFeesEnabledConfig
        }(cnNodeConfig)
      )
  }

  private val futureCompletionTimeout = Timeout(Span(3, Minutes))

  "A validator's traffic top-up loop" when {
    "the validator is configured to not buy extra traffic" should {
      /*
       * bobValidator is used in this test as an example of a validator that will submit requests
       * at the base rate only and will not purchase any extra traffic.
       * TODO(M3-44): Once we're no longer mocking the canton sequencer and the top-up trigger is live for all tests,
       *  it may be worthwhile to create a separate validator for this purpose to properly isolate this test
       *  from other tests making use of bobValidator and any residual traffic balance that may be left over as a result.
       */
      "not top-up at all limiting throughput to base rate" in { implicit env =>
        onboardWalletUser(bobWallet, bobValidator)
        bobValidatorWallet.tap(1000)

        val topupParameters = getTopupParameters(bobValidator)
        topupParameters.topupAmount shouldBe 0L

        actAndCheck(
          "Advance time to trigger automation to purchase extra traffic",
          advanceTimeByPollingInterval(bobValidator),
        )(
          "No top-up happens",
          _ => {
            // NOTE: this check is not very strong, as it is already true at the beginning of the test.
            // We'll recheck that no traffic contract has been created at the end of the test to be more sure.
            lookupCurrentValidatorTraffic(bobValidator) should have length 0
          },
        )

        actAndCheck(
          "Execute taps to consume available traffic balance", {
            val numTaps =
              (maxBaseRateTrafficBalance / DomainFeesConstants.assumedCoinTxSizeBytes.value).toInt
            tryTapsAndCountSuccesses(bobWallet, numTaps)
          },
        )(
          "All taps are successful",
          actResult => actResult._1 shouldBe actResult._2,
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          clue("Execute another tap and see that it fails")(
            tryTapAndReturnOneOnSuccess(bobWallet, smallAmount) shouldBe 0
          ),
          entries =>
            forAtLeast(1, entries)(
              _.message should include("insufficient validator traffic balance to create coins")
            ),
        )

        actAndCheck(
          "Advance time sufficiently for base rate to fill up again", {
            val seconds = Duration.ofSeconds(
              Math
                .ceil(
                  DomainFeesConstants.assumedCoinTxSizeBytes.value / baseRateLimits.rate
                    .doubleValue()
                )
                .toLong
            )
            advanceTime(seconds)
          },
        )(
          "Tap is successful once more",
          _ => tryTapAndReturnOneOnSuccess(bobWallet, smallAmount) shouldBe 1,
        )

        actAndCheck(
          "Advance time by min top-up interval", {
            advanceTimeByMinTopupInterval(bobValidator)
          },
        )(
          "Recheck that no traffic contract has been created",
          _ => lookupCurrentValidatorTraffic(bobValidator) should have length 0,
        )
      }
    }

    "the validator is configured to buy extra traffic" should {
      "top-up quickly enough to achieve the configured target rate" in { implicit env =>
        onboardWalletUser(aliceWallet, aliceValidator)
        clue("Provide sufficient coins to all validators configured to buy extra traffic") {
          aliceValidatorWallet.tap(1000)
          sv1Wallet.tap(1000)
        }

        val topupParameters = getTopupParameters(aliceValidator)
        topupParameters.topupAmount should be > 0L

        actAndCheck(
          "Advance time to trigger automation to purchase extra traffic",
          advanceTimeByPollingInterval(aliceValidator),
        )(
          "The validators are able to successfully top up their extra traffic",
          _ => {
            checkInitialTrafficPurchase(aliceValidator)
            checkInitialTrafficPurchase(sv1Validator)
            purchasedTrafficBalance(
              aliceValidator
            ) should be >= topupParameters.topupAmount.toDouble
            purchasedTrafficBalance(sv1Validator) should be >= getTopupParameters(
              sv1Validator
            ).topupAmount.toDouble
          },
        )

        actAndCheck(
          "Execute taps to consume available traffic balance for alice validator", {
            val totalAvailableTrafficBalance =
              maxBaseRateTrafficBalance + topupParameters.topupAmount
            val numTaps =
              (totalAvailableTrafficBalance / DomainFeesConstants.assumedCoinTxSizeBytes.value).toInt
            tryTapsAndCountSuccesses(aliceWallet, numTaps)
          },
        )(
          "All taps are successful",
          actResult => actResult._1 shouldBe actResult._2,
        )

        val trafficBalanceExhaustedAt = env.environment.clock.now.toEpochMilli

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          clue("Execute another tap and see that it fails for alice validator")(
            tryTapAndReturnOneOnSuccess(aliceWallet, smallAmount) shouldBe 0
          ),
          entries =>
            forAtLeast(1, entries)(
              _.message should include("insufficient validator traffic balance to create coins")
            ),
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          clue("Advance time by half the min top-up interval for alice validator")(
            advanceTimeByMinTopupInterval(aliceValidator, 0.5)
          ),
          entries =>
            clue("Top-up is skipped as not enough time has elapsed since the previous top-up") {
              forAtLeast(1, entries)(line =>
                assert(
                  line.loggerName.contains("validator=aliceValidator") &&
                    line.message.contains("Trying to top-up too soon after previous top-up")
                )
              )
            },
        )

        actAndCheck(
          "Advance time by half the min top-up interval again for alice validator",
          advanceTimeByMinTopupInterval(aliceValidator, 0.5),
        )(
          "Top-up should be successful",
          _ => {
            inside(lookupCurrentValidatorTraffic(aliceValidator)) { case Seq(validatorTraffic) =>
              validatorTraffic.data.validator shouldBe aliceValidator
                .getValidatorPartyId()
                .toProtoPrimitive
              validatorTraffic.data.numPurchases shouldBe 2
              validatorTraffic.data.totalPurchased shouldBe 2 * topupParameters.topupAmount
            }
            purchasedTrafficBalance(
              aliceValidator
            ) should be >= topupParameters.topupAmount.toDouble
          },
        )

        actAndCheck(
          "Execute enough taps to bring traffic balance below top-up threshold", {
            // The low balance threshold at which top-up occurs is the same as the amount of extra traffic
            // purchased on each top-up.
            val topupThreshold = topupParameters.topupAmount
            val currentTrafficBalance = purchasedTrafficBalance(aliceValidator)
            // compute free base rate traffic that has accumulated since the traffic balance was
            // last exhausted by submitting taps
            val elapsedTimeMillis =
              env.environment.clock.now.toEpochMilli - trafficBalanceExhaustedAt
            // consume the accumulated free base-rate traffic balance plus enough extra traffic to bring
            // traffic balance below top-up threshold
            val minTrafficBalanceToBeConsumed =
              baseRateTrafficBalance(
                elapsedTimeMillis
              ) + (currentTrafficBalance - topupThreshold + 1)
            val numTaps =
              (minTrafficBalanceToBeConsumed / DomainFeesConstants.assumedCoinTxSizeBytes.value)
                .setScale(0, RoundingMode.CEILING)
                .toIntExact
            tryTapsAndCountSuccesses(aliceWallet, numTaps)
          },
        )(
          "Taps are successful since sufficient extra traffic balance has been purchased",
          actResult => actResult._1 shouldBe actResult._2,
        )

        actAndCheck(
          "Advance time by min top-up interval for alice validator",
          advanceTimeByMinTopupInterval(aliceValidator),
        )(
          "Top-up is not skipped since the balance is below the top-up threshold",
          _ => {
            inside(lookupCurrentValidatorTraffic(aliceValidator)) { case Seq(validatorTraffic) =>
              validatorTraffic.data.validator shouldBe aliceValidator
                .getValidatorPartyId()
                .toProtoPrimitive
              validatorTraffic.data.numPurchases shouldBe 3
              validatorTraffic.data.totalPurchased shouldBe 3 * topupParameters.topupAmount
            }
            purchasedTrafficBalance(
              aliceValidator
            ) should be >= topupParameters.topupAmount.toDouble
          },
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          clue("Advance time to try and trigger top-up again for alice validator")(
            advanceTimeByMinTopupInterval(aliceValidator, 2)
          ),
          entries =>
            clue("Top-up is skipped as previously purchased traffic has not been consumed") {
              forAtLeast(1, entries)(line =>
                assert(
                  line.loggerName.contains("validator=aliceValidator") &&
                    line.message.contains("sufficient traffic balance remains")
                )
              )
            },
        )

        // Check that Scan correctly reports validator traffic purchases
        clue("Scan reports validator traffic purchases correctly")(
          eventually() {
            advanceTime(tickDurationWithBuffer)
            val roundOfLatestData = sv1Scan.getRoundOfLatestData()._1
            roundOfLatestData should be >= 3L
            val validatorsByPurchasedTraffic = sv1Scan.getTopValidatorsByPurchasedTraffic(
              roundOfLatestData,
              10,
            )
            // Only Alice's validator and the SV1 validator will purchase extra traffic
            // - Alice validator purchases the most traffic (3 times)
            // - SV1 validator purchases traffic once initially and never after that since no traffic goes through it
            // - All the other validators have target throughput set to 0 and never purchase extra traffic at all.
            checkValidatorsByPurchasedTraffic(
              validatorsByPurchasedTraffic,
              Seq(
                t => {
                  t.validator shouldBe aliceValidator.getValidatorPartyId()
                  t.numPurchases shouldBe 3
                  t.totalTrafficPurchased shouldBe topupParameters.topupAmount * 3
                },
                t => {
                  t.validator shouldBe sv1Validator.getValidatorPartyId()
                  t.numPurchases shouldBe 1
                  t.totalTrafficPurchased shouldBe getTopupParameters(sv1Validator).topupAmount
                },
              ),
            )
          }
        )
      }
    }
  }

  "SV automation" should {
    "archive duplicate validator traffic contracts" in { implicit env =>
      val minTopupAmount = sv1Scan.getCoinConfigAsOf(getLedgerTime).globalDomain.fees.minTopupAmount
      actAndCheck(
        "Purchase initial traffic with varying amounts", {
          buyInitialExtraTraffic(bobValidator, bobValidatorWallet, minTopupAmount)
          buyInitialExtraTraffic(bobValidator, bobValidatorWallet, 4 * minTopupAmount)
          buyInitialExtraTraffic(bobValidator, bobValidatorWallet, 2 * minTopupAmount)
          buyInitialExtraTraffic(bobValidator, bobValidatorWallet, 3 * minTopupAmount)
        },
      )(
        "Purchase with highest amount takes effect while other duplicate contracts are archived",
        _ => {
          advanceTimeByPollingInterval(sv1)
          inside(listAllValidatorTrafficContracts(bobValidator)) { case Seq(validatorTraffic) =>
            validatorTraffic.data.totalPurchased shouldBe 4 * minTopupAmount
          }
        },
      )
    }
  }

  private def buyInitialExtraTraffic(
      validatorApp: ValidatorAppBackendReference,
      validatorWallet: WalletAppClientReference,
      amount: Long,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val transferContext = sv1Scan.getTransferContextWithInstances(getLedgerTime)
    val coinRules = sv1Scan.getCoinRules()
    val coin = validatorWallet.tap(100)
    val update = transferContext.coinRules.contract.contractId.exerciseCoinRules_BuyExtraTraffic(
      Seq[v1.coin.TransferInput](
        new v1.coin.transferinput.InputCoin(
          coin.toInterface(v1.coin.Coin.INTERFACE)
        )
      ).asJava,
      new v1.coin.PaymentTransferContext(
        coinRules.contract.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
        new v1.coin.TransferContext(
          transferContext.latestOpenMiningRound.contractId
            .toInterface(v1.round.OpenMiningRound.INTERFACE),
          Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
          Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
          None.toJava,
        ),
      ),
      validatorApp.getValidatorPartyId().toProtoPrimitive,
      new daTypes.either.Left(
        sv1Scan.getCoinConfigAsOf(getLedgerTime).globalDomain.activeDomain
      ),
      amount,
    )
    validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
      validatorApp.config.ledgerApiUser,
      Seq(validatorApp.getValidatorPartyId()),
      Seq(validatorApp.getValidatorPartyId()),
      update,
      disclosedContracts = DisclosedContracts(
        coinRules,
        transferContext.latestOpenMiningRound,
      ).toLedgerApiDisclosedContracts,
    )
  }

  private def lookupCurrentValidatorTraffic(validatorApp: ValidatorAppBackendReference) =
    listAllValidatorTrafficContracts(validatorApp)
      // ignore duplicate validator traffic contracts with lower total purchased traffic
      // TODO(#4914): remove these lines
      .sortWith(_.data.totalPurchased > _.data.totalPurchased)
      .slice(0, 1)

  // TODO(#4914): Temporarily added auxiliary method till we properly dedup domain traffic purchases.
  private def listAllValidatorTrafficContracts(validatorApp: ValidatorAppBackendReference) =
    validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(ValidatorTraffic.COMPANION)(validatorApp.getValidatorPartyId())

  private def baseRateLimits(implicit env: CNNodeTestConsoleEnvironment): BaseRateTrafficLimits = {
    val now = sv1.participantClientWithAdminToken.ledger_api.time.get()
    sv1Scan.getCoinConfigAsOf(now).globalDomain.fees.baseRateTrafficLimits
  }

  private def maxBaseRateTrafficBalance(implicit env: CNNodeTestConsoleEnvironment): BigDecimal = {
    BigDecimal(baseRateLimits.burstWindow.microseconds) / 1e6 * baseRateLimits.rate
  }

  private def baseRateTrafficBalance(
      accumulationTimeMillis: Long
  )(implicit env: CNNodeTestConsoleEnvironment): BigDecimal = {
    val accumulatedBalance = BigDecimal(accumulationTimeMillis) / 1e3 * baseRateLimits.rate
    accumulatedBalance.min(maxBaseRateTrafficBalance)
  }

  private def purchasedTrafficBalance(validatorApp: ValidatorAppBackendReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) =
    sv1Scan.getValidatorTrafficBalance(validatorApp.getValidatorPartyId()).remainingBalance

  private def getTopupParameters(
      validatorApp: ValidatorAppBackendReference
  )(implicit env: CNNodeTestConsoleEnvironment): ExtraTrafficTopupParameters = {
    val now = sv1.participantClientWithAdminToken.ledger_api.time.get()
    ExtraTrafficTopupParameters(
      sv1Scan.getCoinConfigAsOf(now).globalDomain.fees,
      validatorApp.config.domains.global.buyExtraTraffic,
      validatorApp.config.automation.pollingInterval,
    )
  }

  private def tryTapsAndCountSuccesses(wallet: WalletAppClientReference, numTaps: Int) = {
    val successfulTaps = Range
      .inclusive(1, numTaps)
      .toList
      .traverse(i => Future(tryTapAndReturnOneOnSuccess(wallet, BigDecimal(i))))
      .map(_.sum)
      .futureValue(futureCompletionTimeout)
    (successfulTaps, numTaps)
  }

  private def tryTapAndReturnOneOnSuccess(
      wallet: WalletAppClientReference,
      amount: BigDecimal,
  ): Int = {
    logger.debug(s"Tapping $amount CC")
    try {
      wallet.tap(amount)
      1
    } catch {
      case scala.util.control.NonFatal(ex) =>
        logger.debug(s"Ignored exception while tapping $amount CC", ex)
        0
    }
  }

  private def checkInitialTrafficPurchase(
      validatorApp: ValidatorAppBackendReference
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    inside(lookupCurrentValidatorTraffic(validatorApp)) { case Seq(validatorTraffic) =>
      validatorTraffic.data.validator shouldBe validatorApp
        .getValidatorPartyId()
        .toProtoPrimitive
      validatorTraffic.data.numPurchases shouldBe 1
      validatorTraffic.data.totalPurchased shouldBe getTopupParameters(validatorApp).topupAmount
    }
  }

  private def checkValidatorsByPurchasedTraffic(
      actual: Seq[ValidatorPurchasedTraffic],
      expected: Seq[ValidatorPurchasedTraffic => Assertion],
  ): Unit = {
    actual.length shouldBe expected.length
    actual.zip(expected).foreach { case (actualVal, assert) => assert(actualVal) }
  }

}
