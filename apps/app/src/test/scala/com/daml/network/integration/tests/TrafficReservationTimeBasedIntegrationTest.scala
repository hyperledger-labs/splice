package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory.DirectoryEntry
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{CNParticipantClientReference, ValidatorAppBackendReference}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DomainFeesTestUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.HasExecutionContext
import monocle.macros.syntax.lens.*
import com.digitalasset.canton.console.CommandFailure

import scala.concurrent.duration.*
import scala.util.Random
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

class TrafficReservationTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil
    with DomainFeesTestUtil {

  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Directory Entry Description"
  private val rng = new Random(5)
  // Amount of traffic balance to try and consume every time when draining the traffic balance in multiple steps
  private val balanceToConsumePerTry = 300_000L
  // reserved traffic should be enough to do 1 top-up even after traffic balance has been consumed
  private val reservedTraffic = balanceToConsumePerTry + 50_000L

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllValidatorConfigs { case (name, validatorConfig) =>
          val config = validatorConfig
            // set reserved traffic for ValidatorApps
            .focus(_.domains.global.trafficReservedForTopups)
            .replace(NonNegativeNumeric.tryCreate(reservedTraffic))
            // set cache TTL to 0 since this is a time-based test
            .focus(_.domains.global.trafficBalanceCacheTimeToLive)
            .replace(NonNegativeFiniteDuration.ofSeconds(0))
            // reduce minTopupInterval to ensure we can do multiple top-ups during the test
            // keeping this low also means that we do not need to worry about the base rate filling up too much.
            .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
            .replace(NonNegativeFiniteDuration.ofSeconds(2))
          if (name.startsWith("alice")) {
            // targetThroughput should be such that targetThroughput x minTopupInterval is at least 100_000 above the
            // reserved traffic amount (enough to do a top-up, some other txs and leave reserved traffic left over)
            config
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(250_000)))
          } else if (name.contains("bob"))
            config
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
          else
            config
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(300_000)))
        }(config)
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs_(
          _
            // set reserved traffic for SvApps
            .focus(_.domains.global.trafficReservedForTopups)
            .replace(NonNegativeNumeric.tryCreate(reservedTraffic))
            // set cache TTL to 0 since this is a time-based test
            .focus(_.domains.global.trafficBalanceCacheTimeToLive)
            .replace(NonNegativeFiniteDuration.ofSeconds(0))
        )(config)
      )
  }

  "traffic reservation" in { implicit env =>
    val now = env.environment.clock.now
    val domainId =
      DomainId.tryFromString(sv1ScanBackend.getCoinConfigAsOf(now).globalDomain.activeDomain)
    val topupAmount = getTopupParameters(aliceValidatorBackend, now).topupAmount

    try {

      actAndCheck(
        "Trigger top-up for the first time",
        triggerTopup(firstTime = true),
      )(
        "The top-up was completed",
        _ => {
          val traffic =
            getTotalPurchasedTraffic(aliceValidatorBackend.participantClient.id, domainId)
          traffic shouldBe topupAmount

          ensureTopupIsVisible(aliceValidatorBackend.participantClientWithAdminToken)
          val trafficState = getTrafficState(aliceValidatorBackend, domainId)
          trafficState.extraTrafficRemainder.value should be > reservedTraffic
        },
      )

      // Create wallet after initial top-up
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Check that taps go through since there is sufficient traffic balance") {
        noException should be thrownBy aliceWalletClient.tap(smallAmount)
      }

      clue("Drain traffic balance till balance drops below the reserved traffic amount") {
        // Note that we cannot drain the traffic balance in 1 step because the base rate traffic
        // needs to be drained as well and the the base rate configuration cannot be controlled in this test currently.
        // TODO(#7018) - Set base rate to zero for this test once we have the ability to configure the base rate dynamically.
        eventually(2.minutes) {
          drainTrafficBalance(aliceValidatorBackend, balanceToConsumePerTry)
          val trafficState = getTrafficState(aliceValidatorBackend, domainId)
          trafficState.extraTrafficRemainder.value should be <= reservedTraffic
        }
      }

      suppressErrorLog(
        clue("Taps are now throttled") {
          val exception = intercept[CommandFailure](aliceWalletClient.tap(smallAmount))
          exception.getMessage should include regex (".*Traffic balance below amount reserved for top-ups.*")
        }
      )

      suppressErrorLog(
        clue("P2P transfers by aliceValidator fail") {
          val exception = intercept[CommandFailure](
            p2pTransfer(
              aliceValidatorBackend,
              aliceValidatorWalletClient,
              bobValidatorWalletClient,
              bobValidatorBackend.getValidatorPartyId(),
              smallAmount,
            )
          )
          exception.getMessage should include regex (".*Traffic balance below amount reserved for top-ups.*")
        }
      )

      clue("P2P transfers to aliceValidator still go through") {
        bobValidatorWalletClient.tap(10)
        noException should be thrownBy p2pTransfer(
          bobValidatorBackend,
          bobValidatorWalletClient,
          aliceValidatorWalletClient,
          aliceValidatorBackend.getValidatorPartyId(),
          5,
        )
      }

      actAndCheck(
        "Trigger top-up loop",
        triggerTopup(),
      )(
        "Top-ups still succeeds",
        _ => {
          val traffic =
            getTotalPurchasedTraffic(aliceValidatorBackend.participantClient.id, domainId)
          traffic shouldBe (2 * topupAmount)

          ensureTopupIsVisible(aliceValidatorBackend.participantClientWithAdminToken)
          val trafficState = getTrafficState(aliceValidatorBackend, domainId)
          trafficState.extraTrafficRemainder.value should be > reservedTraffic
        },
      )

      clue("Taps go through once more after top-up") {
        noException should be thrownBy aliceWalletClient.tap(smallAmount)
      }
    } finally {
      // This step drains the traffic balance to ensure that restarting the test against the
      // same Canton instance works. Specifically, this ensures that the trafficBalance at the time
      // initialTrafficState is recorded in the SvcRules during Svc founding is not greater than
      // the topupAmount. If we do not do this, the first traffic purchase in a subsequent run will not
      // affect the trafficBalance at all since the reconciliation loop will not see the traffic limit increase.
      clue("Drain traffic balance") {
        eventually() {
          val trafficStateBefore = getTrafficState(aliceValidatorBackend, domainId)
          val diff = trafficStateBefore.extraTrafficRemainder.value - topupAmount
          if (diff >= 0L)
            drainTrafficBalance(aliceValidatorBackend, diff)
          diff should be < 0L
        }
      }
    }

  }

  private def triggerTopup(
      firstTime: Boolean = false
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    // Advance time to trigger top-up loop
    if (firstTime)
      advanceTimeByPollingInterval(aliceValidatorBackend)
    else
      advanceTimeByMinTopupInterval(aliceValidatorBackend)
    // Advance time to trigger traffic reconciliation loop
    advanceTimeByPollingInterval(aliceValidatorBackend)
  }

  private def ensureTopupIsVisible(participantClient: CNParticipantClientReference) = {
    // Do a ping to force a new event to be sequenced.  Without this, the latest sequenced event's timestamp
    // could still be before the effective timestamp of the top up, in which case the top-up will not be
    // visible to the participant.
    participantClient.health.ping(participantClient.id)
  }

  private def tryCreatingDirectoryEntry(
      participantClient: CNParticipantClientReference,
      dirParty: PartyId,
      dirEntryName: String,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val now = env.environment.clock.now
    logger.debug("Creating randomly generated directory entry")
    suppressErrorLog(
      try {
        participantClient.ledger_api_extensions.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            dirEntryName,
            testEntryUrl,
            testEntryDescription,
            now.plus(java.time.Duration.ofDays(90)).toInstant,
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        1
      } catch {
        case NonFatal(e) =>
          logger.debug(s"Ignoring exception while creating directory entry: $e")
          0
      }
    )
  }

  private def drainTrafficBalance(validatorApp: ValidatorAppBackendReference, amount: Long)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    // Directory entries get compressed in the ledger. So we need a formula to convert
    // the amount of traffic balance to consume in bytes to the size of the directory entry to create.
    // The values used here for this purpose have been determined empirically.
    val dirEntrySize = (1.3 * amount - 4270).toInt
    val dirEntryName = rng.alphanumeric.take(dirEntrySize).mkString
    tryCreatingDirectoryEntry(
      validatorApp.participantClientWithAdminToken,
      validatorApp.getValidatorPartyId(),
      dirEntryName,
    )
  }

  private def suppressErrorLog[A](within: => A): A = {
    loggerFactory.assertLoggedWarningsAndErrorsSeq(
      within,
      lines =>
        forAll(lines)(
          _.message should include regex (".*Traffic balance below amount reserved for top-ups.*")
        ),
    )
  }

}
