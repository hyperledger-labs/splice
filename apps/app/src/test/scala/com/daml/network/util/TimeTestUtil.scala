package com.daml.network.util

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.{TimeLock, TransferOutput}
import com.daml.network.codegen.java.cc.coin.SvcReward
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.console.*
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.{DomainId, PartyId}
import org.scalatest.Assertion

import java.time.{Duration, Instant}
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TimeTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences & WalletTestUtil =>

  def getLedgerTime(implicit env: CNNodeTestConsoleEnvironment) =
    svc.participantClient.ledger_api.time.get()

  // Advance time by `duration`; works only if the used Canton instance uses simulated time.
  protected def advanceTime(
      duration: Duration
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
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
            svc.participantClient.ledger_api_extensions.acs
              .filterJava(SvcReward.COMPANION)(scan.getSvcPartyId()) shouldBe empty
          }
          // We additionally sleep 1.5s to give some extra time for activity to quiesce.
          Threading.sleep(1500)
        }

        // it doesn't seem to matter which participant we run these from - all get synced
        val now = svc.participantClient.ledger_api.time.get()
        try {
          // actually advance the time
          logger.info(s"advancing time by $duration ...")
          svc.participantClient.ledger_api.time.set(now, now.plus(duration))
        } catch {
          case _: CommandFailure =>
            fail(
              "Could not advance time. " +
                "Is Canton configured with `parameters.clock.type = sim-clock`?"
            )
        }
        // We don't get feedback about the success of setting the time here, so we check ourselves.
        if (svc.participantClient.ledger_api.time.get() == now) {
          fail(
            "Could not advance time. " +
              "Are participants configured with `testing-time.type = monotonic-time`?"
          )
        }
      }
    }
  }

  def transferOutputCoin(
      receiver: PartyId,
      receiverFeeRatio: BigDecimal,
      amount: BigDecimal,
  ): v1.coin.TransferOutput = {
    new TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      None.toJava,
    )
  }

  def transferOutputLockedCoin(
      receiver: PartyId,
      lockHolders: Seq[PartyId],
      receiverFeeRatio: BigDecimal,
      amount: BigDecimal,
      expiredDuration: Duration,
  )(implicit cnNodeEnv: CNNodeTestConsoleEnvironment): v1.coin.TransferOutput = {
    val expiredAt = cnNodeEnv.environment.clock.now.add(expiredDuration)
    val expiration = Codec.decode(Codec.Timestamp)(expiredAt.underlying.micros).value

    new TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      Some(
        new TimeLock(
          lockHolders.map(_.toProtoPrimitive).asJava,
          expiration.toInstant,
        )
      ).toJava,
    )
  }

  def lockCoins(
      userValidator: ValidatorAppBackendReference,
      userParty: PartyId,
      validatorParty: PartyId,
      coins: Seq[HttpWalletAppClient.CoinPosition],
      amount: BigDecimal,
      scan: ScanAppBackendReference,
      expiredDuration: Duration,
  )(implicit cnNodeEnv: CNNodeTestConsoleEnvironment): Unit =
    clue(s"Locking $amount coins for $userParty") {
      val coin = coins.find(_.effectiveAmount >= amount).value
      val coinRules = scan.getCoinRules()
      val transferContext = scan.getUnfeaturedAppTransferContext(getLedgerTime)
      val openRound = scan.getLatestOpenMiningRound(getLedgerTime)

      userValidator.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
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
                transferOutputLockedCoin(
                  userParty,
                  Seq(userParty),
                  BigDecimal(0.0),
                  amount,
                  expiredDuration,
                )
              ).asJava,
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

  /** Directly exercises the CoinRules_Transfer choice.
    * Note that all parties participating in the transfer need to be hosted on the same participant
    */
  def rawTransfer(
      userValidator: ValidatorAppBackendReference,
      userId: String,
      userParty: PartyId,
      validatorParty: PartyId,
      coin: HttpWalletAppClient.CoinPosition,
      outputs: Seq[v1.coin.TransferOutput],
      domainId: Option[DomainId] = None,
  )(implicit cnNodeEnv: CNNodeTestConsoleEnvironment) = {
    val coinRules = scan.getCoinRules()
    val transferContext = scan.getUnfeaturedAppTransferContext(getLedgerTime)
    val openRound = scan.getLatestOpenMiningRound(getLedgerTime)

    val authorizers =
      Seq(userParty, validatorParty) ++ outputs.map(o => PartyId.tryFromProtoPrimitive(o.receiver))

    userValidator.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = authorizers.distinct,
      readAs = Seq.empty,
      update = transferContext.coinRules
        .exerciseCoinRules_Transfer(
          new v1.coin.Transfer(
            userParty.toProtoPrimitive,
            userParty.toProtoPrimitive,
            Seq[v1.coin.TransferInput](
              new v1.coin.transferinput.InputCoin(
                coin.contract.contractId.toInterface(v1.coin.Coin.INTERFACE)
              )
            ).asJava,
            outputs.asJava,
          ),
          new v1.coin.TransferContext(
            transferContext.openMiningRound,
            Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
            Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
            // note: we don't provide a featured app right as sender == provider
            None.toJava,
          ),
        ),
      domainId = domainId,
      disclosedContracts = Seq(coinRules.toDisclosedContract, openRound.toDisclosedContract),
    )
  }

  /** This function advances time by ~one tick and waits for the SVC round management automation for open, summarizing
    * and issuing rounds to finish processing the events generated by the new time.
    * This function does not wait for the closed round automation.
    */
  def advanceRoundsByOneTick(implicit env: CNNodeTestConsoleEnvironment) = {
    advanceTimeAndWaitForRoundAutomation(tickDurationWithBuffer)
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
        eventually() {

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
    val now = svc.participantClient.ledger_api.time.get().toInstant
    val (openRounds, _) = scan.getOpenAndIssuingMiningRounds()
    val earliestOpen = openRounds
      .filter(round => now.isBefore(round.payload.targetClosesAt))
      .map(_.payload.opensAt)
      .min
    if (now.isBefore(earliestOpen)) {
      advanceTime(Duration.between(now, earliestOpen))
    }
  }

  def advanceTimeByPollingInterval(appRef: CNNodeAppBackendReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = advanceTime(
    appRef.config.automation.pollingInterval.asJava
  )

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
      env: CNNodeTestConsoleEnvironment
  ) = {
    val validatorTopupParameters = ExtraTrafficTopupParameters(
      scan.getCoinRules().payload.configSchedule.currentValue.globalDomain.fees,
      validatorAppRef.config.domains.global.buyExtraTraffic,
      validatorAppRef.config.automation.pollingInterval,
    )
    advanceTime((validatorTopupParameters.minTopupInterval * multiple).asJava)
  }

  def getSortedOpenMiningRounds(
      participantClient: CNParticipantClientReference,
      validatorPartyId: PartyId,
  ): Seq[OpenMiningRound.Contract] = participantClient.ledger_api_extensions.acs
    .filterJava(OpenMiningRound.COMPANION)(validatorPartyId)
    .sortBy(_.data.round.number)

  def getSortedIssuingRounds(
      participantClient: CNParticipantClientReference,
      validatorPartyId: PartyId,
  ): Seq[IssuingMiningRound.Contract] = participantClient.ledger_api_extensions.acs
    .filterJava(IssuingMiningRound.COMPANION)(
      validatorPartyId
    )
    .sortBy(_.data.round.number)

  /** Create a new config schedule reusing the active domain value from the existing one.
    * Intended for testing only. Please generalize if you need more functionality
    */
  def createConfigSchedule(
      currentSchedule: Schedule[Instant, CoinConfig[USD]],
      newSchedules: (Duration, cc.coinconfig.CoinConfig[cc.coinconfig.USD])*
  )(implicit env: CNNodeTestConsoleEnvironment): Schedule[Instant, CoinConfig[USD]] = {
    val now = svc.participantClientWithAdminToken.ledger_api.time.get()
    val configSchedule = {
      new cc.schedule.Schedule(
        mkUpdatedCoinConfig(currentSchedule, defaultTickDuration),
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
      walletBackend: CNNodeAppBackendReference,
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
