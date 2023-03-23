package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.{TimeLock, TransferOutput}
import com.daml.network.codegen.java.cc.coin.SvcReward
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.console.{
  CNRemoteParticipantReference,
  LocalCNNodeAppReference,
  ScanAppBackendReference,
  WalletAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

import java.time.Duration
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TimeTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences & WalletTestUtil =>

  def getLedgerTime(implicit env: CNNodeTestConsoleEnvironment) =
    svc.remoteParticipant.ledger_api.time.get()

  // Advance time by `duration`; works only if the used Canton instance uses simulated time.
  protected def advanceTime(
      duration: Duration
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    clue(s"attempting to advance time by $duration") {
      if (duration.isNegative()) {
        fail("Cannot advance time by negative duration.");
      } else if (!duration.isZero()) {
        // As discussed in depth on https://github.com/DACH-NY/the-real-canton-coin/issues/3091
        // we need to wait until the system is quiescent enough to avoid warnings on the
        // sequencer due to the massive clock skew induced by bumping the time.
        //
        // More concretely, in-flight commands submitted before advancing the time,
        // but being processed on the sequencer after advancing time
        // get dropped by the sequencer due to exceeding max-sequencing-time.
        // While we do recover from such an issue, we recover from it once the participant
        // times out with LOCAL_VERDICT_TIMEOUT. That timeout is measured in wall clock
        // so we will wait the full participantResponseTimeout (30s by default) which
        // then results in `eventually`’s in tests never completing.
        //
        // The waiting implement below should ensure that existing background automation (e.g. coin merging)
        // can complete in-flight commands before we change the time. The assumption here
        // is that our period automation also takes sim time into account so if
        // the time does not change, it won’t continue sending commands.
        //
        // The main source of activity are the follow-up action after advancing a round.
        // We thus first wait for a successful Svc reward collection; and bet that the
        // collection of app and validator rewards takes about the same time.
        //
        clue("wait for the system to quiet down after a round change") {
          eventually() {
            svc.remoteParticipant.ledger_api_extensions.acs
              .filterJava(SvcReward.COMPANION)(scan.getSvcPartyId()) shouldBe empty
          }
          // We additionally sleep 1.5s to give some extra time for activity to quiesce.
          Threading.sleep(1500)
        }

        // it doesn't seem to matter which participant we run these from - all get synced
        val now = svc.remoteParticipant.ledger_api.time.get()
        try {
          // actually advance the time
          logger.info(s"advancing time by $duration ...")
          svc.remoteParticipant.ledger_api.time.set(now, now.plus(duration))
        } catch {
          case _: CommandFailure =>
            fail(
              "Could not advance time. " +
                "Is Canton configured with `parameters.clock.type = sim-clock`?"
            )
        }
        // We don't get feedback about the success of setting the time here, so we check ourselves.
        if (svc.remoteParticipant.ledger_api.time.get() == now) {
          fail(
            "Could not advance time. " +
              "Are participants configured with `testing-time.type = monotonic-time`?"
          )
        }
      }
    }
  }

  def lockCoins(
      userWallet: WalletAppBackendReference,
      userParty: PartyId,
      validatorParty: PartyId,
      coins: Seq[HttpWalletAppClient.CoinPosition],
      amount: BigDecimal,
      scan: ScanAppBackendReference,
      expiredDuration: Duration,
  )(implicit cnNodeEnv: CNNodeTestConsoleEnvironment): Unit =
    clue(s"Locking $amount coins for $userParty") {
      val coinOpt = coins.find(_.effectiveAmount >= amount)
      val coinRules = scan.getCoinRules()
      val transferContext = scan.getUnfeaturedAppTransferContext(getLedgerTime)
      val openRound = scan.getLatestOpenMiningRound(getLedgerTime)

      val expiredAt = cnNodeEnv.environment.clock.now.add(expiredDuration)
      val expirationOpt = Codec.decode(Codec.Timestamp)(expiredAt.underlying.micros)

      (coinOpt, expirationOpt) match {
        case (Some(coin), Right(expiration)) => {
          userWallet.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
            Seq(userParty, validatorParty),
            optTimeout = None,
            commands = transferContext.coinRules
              .exerciseCoinRules_Transfer(
                new v1.coin.Transfer(
                  userParty.toProtoPrimitive,
                  userParty.toProtoPrimitive,
                  Seq[v1.coin.TransferInput](
                    new v1.coin.transferinput.InputCoin(
                      coin.contract.contractId.toInterface(v1.coin.Coin.INTERFACE)
                    )
                  ).asJava,
                  Seq[v1.coin.TransferOutput](
                    new TransferOutput(
                      userParty.toProtoPrimitive,
                      BigDecimal(0.0).bigDecimal,
                      amount.bigDecimal,
                      Some(
                        new TimeLock(
                          Seq(userParty.toProtoPrimitive).asJava,
                          expiration.toInstant,
                        )
                      ).toJava,
                    )
                  ).asJava,
                  "lock coins",
                ),
                new v1.coin.TransferContext(
                  transferContext.openMiningRound,
                  Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
                  Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
                  // note: we don't provide a featured app right as sender == provider
                  None.toJava,
                ),
              )
              .commands
              .asScala
              .toSeq,
            disclosedContracts = Seq(coinRules.toDisclosedContract, openRound.toDisclosedContract),
          )
        }
        case _ => {
          coinOpt shouldBe a[Some[_]]
          expirationOpt shouldBe a[Right[_, _]]
        }
      }
    }

  /** This function advances time by ~one tick and waits for the SVC round management automation for open, summarizing
    * and issuing rounds to finish processing the events generated by the new time.
    * This function does not wait for the closed round automation.
    */
  def advanceRoundsByOneTick(implicit env: CNNodeTestConsoleEnvironment) = {
    advanceTimeAndWaitForRoundAutomation(Duration.ofSeconds(160))
  }

  @nowarn("msg=match may not be exhaustive")
  def advanceTimeAndWaitForRoundAutomation(
      duration: Duration
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val (previousOpenRounds, previousIssuingRounds) = scan.getOpenAndIssuingMiningRounds()
    val Seq(lowestOpen, middleOpen, highestOpen) = previousOpenRounds.map(_.payload.round.number)

    // not exactly 150s because of the skew parameter.
    actAndCheck("advancing time", advanceTime(duration))(
      s"waiting for open and issuing round automation (should create OpenMiningRound ${highestOpen + 1}, should advance IssuingMiningRounds $previousIssuingRounds",
      _ =>
        eventually(5.seconds) {

          val (newOpenRounds, newIssuingRounds) =
            scan.getOpenAndIssuingMiningRounds()

          val Seq(newLowestOpen, newMiddleOpen, newHighestOpen) =
            newOpenRounds.map(_.payload.round.number)

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
            forExactly(1, newIssuingRounds)(round => round.payload.round.number shouldBe lowestOpen)
          } else {
            val Seq(lowestIssuing, middleIssuing, highestIssuing) =
              previousIssuingRounds.map(_.payload.round.number)
            newIssuingRounds should have size 3
            val Seq(newLowestIssuing, newMiddleIssuing, newHighestIssuing) =
              newIssuingRounds.map(_.payload.round.number)

            newLowestIssuing shouldBe lowestIssuing + 1
            newLowestIssuing shouldBe middleIssuing
            newMiddleIssuing shouldBe highestIssuing
            newHighestIssuing shouldBe highestIssuing + 1
          }
        },
    )
  }

  /** This function advances time until at least one mining round that is not
    *  past its target closing time is open. The function fails if no open
    *  mining round exists where this is possible.
    */
  def advanceTimeToRoundOpen(implicit env: CNNodeTestConsoleEnvironment) = {
    val now = svc.remoteParticipant.ledger_api.time.get().toInstant
    val (openRounds, _) = scan.getOpenAndIssuingMiningRounds()
    val earliestOpen = openRounds
      .filter(round => now.isBefore(round.payload.targetClosesAt))
      .map(_.payload.opensAt)
      .min
    if (now.isBefore(earliestOpen)) {
      advanceTime(Duration.between(now, earliestOpen))
    }
  }

  def advanceTimeByPollingInterval(appRef: LocalCNNodeAppReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = advanceTime(
    appRef.config.automation.pollingInterval.duration
  )

  def getSortedOpenMiningRounds(
      remoteParticipant: CNRemoteParticipantReference,
      validatorPartyId: PartyId,
  ): Seq[OpenMiningRound.Contract] = remoteParticipant.ledger_api_extensions.acs
    .filterJava(OpenMiningRound.COMPANION)(validatorPartyId)
    .sortBy(_.data.round.number)

  def getSortedIssuingRounds(
      remoteParticipant: CNRemoteParticipantReference,
      validatorPartyId: PartyId,
  ): Seq[IssuingMiningRound.Contract] = remoteParticipant.ledger_api_extensions.acs
    .filterJava(IssuingMiningRound.COMPANION)(
      validatorPartyId
    )
    .sortBy(_.data.round.number)

  def p2pTransferAndTriggerAutomation(
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      advanceTimeBy: Duration = Duration.ofSeconds(1),
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    p2pTransfer(senderWallet, receiverWallet, receiver, amount)
    eventually() {
      // wait until we observe the accepted transfer offer
      receiverWallet.listAcceptedTransferOffers() should have size 1
    }
    // ... before we advance time to trigger the automation.
    advanceTime(advanceTimeBy)
  }

  def createConfigSchedule(
      newSchedules: (Duration, cc.coinconfig.CoinConfig[cc.coinconfig.USD])*
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()
    val configSchedule = {
      new cc.schedule.Schedule(
        mkCoinConfig(defaultTickDuration),
        newSchedules
          .map { case (durationUntilScheduled, config) =>
            new Tuple2(
              now.add(durationUntilScheduled).toInstant,
              config,
            )
          }
          .toList
          .asJava,
      )
    }
    configSchedule
  }

  def cancelAllSubscriptions(
      walletClient: WalletAppClientReference,
      walletBackend: LocalCNNodeAppReference,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Assertion = {
    clue("Cancel subscription to avoid affecting other test cases") {
      eventually() {
        // Trigger a payment->idle state transition if needed
        // Done inside the eventually as it can go both ways: trigger a make payment or completing a payment.
        // This assumes that our subscriptions use payment intervals that are much longer than the triggers' polling interval.
        advanceTimeByPollingInterval(walletBackend)
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
