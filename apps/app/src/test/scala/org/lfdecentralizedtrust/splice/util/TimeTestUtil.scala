package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  TestCommon,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.util.ExtraTrafficTopupParameters
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

import java.time.Duration
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import com.digitalasset.canton.data.CantonTimestamp

trait TimeTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & WalletTestUtil =>

  def getLedgerTime(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.participantClient.ledger_api.time.get()

  // Advance time by `duration`; works only if the used Canton instance uses simulated time.
  protected def advanceTime(
      duration: Duration
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"attempting to advance time by $duration") {
      if (duration.isNegative()) {
        fail("Cannot advance time by negative duration.");
      } else if (!duration.isZero()) {
        // As discussed in depth on https://github.com/DACH-NY/canton-network-node/issues/3091
        // we need to wait until the system is quiescent enough to avoid warnings on the
        // sequencer due to the massive clock skew induced by bumping the time.
        //
        // More concretely, in-flight commands submitted before advancing the time,
        // but being processed on the sequencer after advancing time
        // get dropped by the sequencer due to exceeding max-sequencing-time.
        // While we do recover from such an issue, we recover from it once the participant
        // times out with LOCAL_VERDICT_TIMEOUT. That timeout is measured in wall clock
        // so we will wait the full participantResponseTimeout (30s by default) which
        // then results in `eventually`'s in tests never completing.
        //
        // The waiting implement below should ensure that existing background automation (e.g. amulet merging)
        // can complete in-flight commands before we change the time. The assumption here
        // is that our period automation also takes sim time into account so if
        // the time does not change, it won't continue sending commands.
        //
        // The main source of activity are the follow-up action after advancing a round.
        // We thus first wait for a successful Dso reward collection; and bet that the
        // collection of app and validator rewards takes about the same time.
        //
        clue("wait for the system to quiet down after a round change") {
          // We additionally sleep 1.5s to give some extra time for activity to quiesce.
          Threading.sleep(1500)
        }

        // it doesn't seem to matter which participant we run these from - all get synced
        val now = sv1Backend.participantClient.ledger_api.time.get()
        try {
          // actually advance the time
          logger.info(s"advancing time by $duration ...")
          sv1Backend.participantClient.ledger_api.time.set(now, now.plus(duration))
        } catch {
          case _: CommandFailure =>
            fail(
              "Could not advance time. " +
                "Is Canton configured with `parameters.clock.type = sim-clock`?"
            )
        }
        // We don't get feedback about the success of setting the time here, so we check ourselves.
        if (sv1Backend.participantClient.ledger_api.time.get() == now) {
          fail(
            "Could not advance time. " +
              "Are participants configured with `testing-time.type = monotonic-time`?"
          )
        }
      }
    }
  }

  /** Directly exercises the AmuletRules_Transfer choice.
    * Note that all parties participating in the transfer need to be hosted on the same participant
    */
  def rawTransfer(
      userValidator: ValidatorAppBackendReference,
      userId: String,
      userParty: PartyId,
      validatorParty: PartyId,
      amulet: HttpWalletAppClient.AmuletPosition,
      outputs: Seq[splice.amuletrules.TransferOutput],
      now: CantonTimestamp,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val amuletRules = sv1ScanBackend.getAmuletRules()
    val transferContext = sv1ScanBackend.getUnfeaturedAppTransferContext(now)
    val openRound = sv1ScanBackend.getLatestOpenMiningRound(now)

    val authorizers =
      Seq(userParty, validatorParty) ++ outputs.map(o => PartyId.tryFromProtoPrimitive(o.receiver))

    val disclosure = DisclosedContracts.forTesting(amuletRules, openRound)

    userValidator.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      applicationId = userId,
      actAs = authorizers.distinct,
      readAs = Seq.empty,
      commands = transferContext.amuletRules
        .exerciseAmuletRules_Transfer(
          new splice.amuletrules.Transfer(
            userParty.toProtoPrimitive,
            userParty.toProtoPrimitive,
            Seq[splice.amuletrules.TransferInput](
              new splice.amuletrules.transferinput.InputAmulet(
                amulet.contract.contractId
              )
            ).asJava,
            outputs.asJava,
          ),
          new splice.amuletrules.TransferContext(
            transferContext.openMiningRound,
            Map.empty[Round, IssuingMiningRound.ContractId].asJava,
            Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
            // note: we don't provide a featured app right as sender == provider
            None.toJava,
          ),
        )
        .commands
        .asScala
        .toSeq,
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
      optTimeout = None,
    )
  }

  /** This function advances time by ~one tick and waits for the DSO round management automation for open, summarizing
    * and issuing rounds to finish processing the events generated by the new time.
    * This function does not wait for the closed round automation.
    *
    * See the docstring for `advanceTimeAndWaitForRoundAutomation` for caveats on its usage.
    */
  def advanceRoundsByOneTick(implicit env: SpliceTestConsoleEnvironment) = {
    advanceTimeAndWaitForRoundAutomation(tickDurationWithBuffer)
  }

  /** Advance time and wait for Scan to finish ingesting the events generated by the DSO round management
    * automation for open, summarizing and issuing rounds.
    *
    * NOTE: Use this function only in time-based tests that precisely control the round they are currently in and
    * want to progress to the next round. Due to the eventual consistency of the Scan store, there is a potential
    * for arbitrary number of rounds to be ingested by the store in between the two calls to the Scan API, resulting in
    * test failures.
    *
    * TODO(#5317): Modify this to be less susceptible to flakes.
    * TODO (#7609): consider using automation control
    */
  @nowarn("msg=match may not be exhaustive")
  def advanceTimeAndWaitForRoundAutomation(
      duration: Duration
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val (previousOpenRounds, previousIssuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
    val Seq(lowestOpen, middleOpen, highestOpen) =
      previousOpenRounds.map(_.contract.payload.round.number)

    // not exactly 150s because of the skew parameter.
    actAndCheck("advancing time", advanceTime(duration))(
      s"waiting for open and issuing round automation (should create OpenMiningRound ${highestOpen + 1}, should advance IssuingMiningRounds $previousIssuingRounds",
      _ => {

        val (newOpenRounds, newIssuingRounds) =
          sv1ScanBackend.getOpenAndIssuingMiningRounds()

        val Seq(newLowestOpen, newMiddleOpen, newHighestOpen) =
          newOpenRounds.map(_.contract.payload.round.number)

        (
          newLowestOpen,
          newLowestOpen,
          newMiddleOpen,
          newHighestOpen,
        ) shouldBe (lowestOpen + 1, middleOpen, highestOpen, highestOpen + 1)

        if (newIssuingRounds.size < 3 || previousIssuingRounds.size < 3) {
          // This can happen both at the beginning when we start out with fewer issuing rounds
          // or if we advance for more than one tick which will result in potentially
          // all old issuing rounds being archived and only one new round being created
          // for the open round that we just archived.
          forExactly(1, newIssuingRounds)(round =>
            round.contract.payload.round.number shouldBe lowestOpen
          )
        } else {
          inside(previousIssuingRounds.map(_.contract.payload.round.number)) {
            case Seq(lowestIssuing, middleIssuing, highestIssuing) =>
              newIssuingRounds should have size 3
              val Seq(newLowestIssuing, newMiddleIssuing, newHighestIssuing) =
                newIssuingRounds.map(_.contract.payload.round.number)

              newLowestIssuing shouldBe lowestIssuing + 1
              newLowestIssuing shouldBe middleIssuing
              newMiddleIssuing shouldBe highestIssuing
              newHighestIssuing shouldBe highestIssuing + 1
          }
        }
      },
    )
  }

  /** This function advances time until at least one mining round that is not
    *  past its target closing time is open. The function fails if no open
    *  mining round exists where this is possible.
    */
  def advanceTimeToRoundOpen(implicit env: SpliceTestConsoleEnvironment) = {
    val now = sv1Backend.participantClient.ledger_api.time.get().toInstant
    val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
    val earliestOpen = openRounds
      .filter(round => now.isBefore(round.contract.payload.targetClosesAt))
      .map(_.contract.payload.opensAt)
      .min
    if (now.isBefore(earliestOpen)) {
      advanceTime(Duration.between(now, earliestOpen))
    }
  }

  /** This function advances time sufficiently to trigger an extra traffic top-up
    * for the provided validator.
    *
    * Note that it does not guarantee that a top-up will occur because the validator
    * may still have enough traffic balance remaining to not warrant another top-up.
    */
  def advanceTimeByMinTopupInterval(
      validatorAppRef: ValidatorAppBackendReference,
      multiple: Double = 1.0,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val now = sv1Backend.participantClient.ledger_api.time.get()
    val validatorTopupParameters = ExtraTrafficTopupParameters(
      validatorAppRef.config.domains.global.buyExtraTraffic.targetThroughput,
      validatorAppRef.config.domains.global.buyExtraTraffic.minTopupInterval,
      sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.fees.minTopupAmount,
      validatorAppRef.config.automation.topupTriggerPollingInterval_,
    )
    advanceTime((validatorTopupParameters.minTopupInterval * multiple).asJava)
  }

  def getSortedOpenMiningRounds(
      participantClient: ParticipantClientReference,
      validatorPartyId: PartyId,
  ): Seq[OpenMiningRound.Contract] = participantClient.ledger_api_extensions.acs
    .filterJava(OpenMiningRound.COMPANION)(validatorPartyId)
    .sortBy(_.data.round.number)

  def getSortedIssuingRounds(
      participantClient: ParticipantClientReference,
      validatorPartyId: PartyId,
  ): Seq[IssuingMiningRound.Contract] = participantClient.ledger_api_extensions.acs
    .filterJava(IssuingMiningRound.COMPANION)(
      validatorPartyId
    )
    .sortBy(_.data.round.number)

  def cancelAllSubscriptions(
      walletClient: WalletAppClientReference
  ): Assertion = {
    clue("Cancel subscription to avoid affecting other test cases") {
      eventually() {
        // Cancel all subscriptions that are currently idle
        walletClient
          .listSubscriptions()
          .foreach(subscription =>
            subscription.state match {
              case HttpWalletAppClient.SubscriptionIdleState(c) =>
                try {
                  walletClient.cancelSubscription(c.contractId)
                } catch {
                  case ex: CommandFailure =>
                    logger.debug("Ignoring failed attempt to cancel subscription", ex)
                }
              case state =>
                logger.debug(s"Skipping cancellation of non-idle subscription in state: $state")
            }
          )
        walletClient.listSubscriptions() shouldBe empty
      }
    }
  }
}
