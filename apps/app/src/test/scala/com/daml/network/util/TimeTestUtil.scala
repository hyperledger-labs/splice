package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.{TimeLock, TransferOutput}
import com.daml.network.codegen.java.cc.coin.SvcReward
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  ScanAppBackendReference,
  WalletAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.Duration
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TimeTestUtil extends CoinTestCommon {
  this: CommonCoinAppInstanceReferences & WalletTestUtil =>

  def getLedgerTime(implicit env: CoinTestConsoleEnvironment) =
    svc.remoteParticipant.ledger_api.time.get()

  // Advance time by `duration`; works only if the used Canton instance uses simulated time.
  protected def advanceTime(
      duration: Duration
  )(implicit env: CoinTestConsoleEnvironment): Unit = {
    clue(s"attempting to advance time by $duration") {
      if (duration.isNegative()) {
        fail("Cannot advance time by negative duration.");
      } else if (!duration.isZero()) {
        // We sleep for 1s before changing the time here. This avoids inflight commands
        // getting dropped by the sequencer due to exceeding max-sequencing-time.
        // While we do recover from such an issue, we recover from it once the participant
        // times out with LOCAL_VERDICT_TIMEOUT. That timeout is measured in wall clock
        // so we will wait the full participantResponseTimeout (30s by default) which
        // then results in `eventually`’s in tests never completing.
        // Waiting 1s should ensure that existing background automation (e.g. coin merging)
        // can complete in-flight commands before we change the time. The assumption here
        // is that our period automation also takes sim time into account so if
        // the time does not change, it won’t continue sending commands.
        Threading.sleep(1000)
        // Svc reward collection seems to take particularly long so wait for all of them to be collected before advancing.
        eventually() {
          svc.remoteParticipant.ledger_api_extensions.acs
            .filterJava(SvcReward.COMPANION)(scan.getSvcPartyId()) shouldBe empty
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

  /** Rejects an accepted app payment request. */
  def rejectAcceptedAppPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      deliveryOffer: testWalletCodegen.TestDeliveryOffer.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val tc = scan.getTransferContextWithInstances(getLedgerTime)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val payment = findAcceptedAppPaymentRequests(remoteParticipant, userParty, deliveryOffer)
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = payment.id.exerciseAcceptedAppPayment_Reject(appTc),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  private def findAcceptedAppPaymentRequests(
      remoteParticipant: CoinRemoteParticipantReference,
      userParty: PartyId,
      deliveryOffer: testWalletCodegen.TestDeliveryOffer.ContractId,
  ) = {
    remoteParticipant.ledger_api_extensions.acs
      .filterJava(paymentCodegen.AcceptedAppPayment.COMPANION)(
        userParty,
        (c: paymentCodegen.AcceptedAppPayment.Contract) =>
          c.data.deliveryOffer.contractId == deliveryOffer.contractId,
      )
      .headOption
      .getOrElse(
        sys.error(s"No accepted app payment request found with delivery offer ${deliveryOffer}")
      )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectAcceptedAppPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      deliveryOffer: testWalletCodegen.TestDeliveryOffer.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val payment = findAcceptedAppPaymentRequests(remoteParticipant, userParty, deliveryOffer)
    val tc = scan.getTransferContextWithInstances(getLedgerTime)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = payment.id.exerciseAcceptedAppPayment_Collect(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  def lockCoins(
      userWallet: WalletAppBackendReference,
      userParty: PartyId,
      validatorParty: PartyId,
      coins: Seq[HttpWalletAppClient.CoinPosition],
      amount: BigDecimal,
      scan: ScanAppBackendReference,
      expiredDuration: Duration,
  )(implicit coinEnv: CoinTestConsoleEnvironment): Unit =
    clue(s"Locking $amount coins for $userParty") {
      val coinOpt = coins.find(_.effectiveAmount >= amount)
      val coinRules = scan.getCoinRules()
      val transferContext = scan.getUnfeaturedAppTransferContext(getLedgerTime)
      val openRound = scan.getLatestOpenMiningRound(getLedgerTime)

      val expiredAt = coinEnv.environment.clock.now.add(expiredDuration)
      val expirationOpt = Proto.decode(Proto.Timestamp)(expiredAt.underlying.micros)

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
  def advanceRoundsByOneTick(implicit env: CoinTestConsoleEnvironment) = {
    advanceTimeAndWaitForRoundAutomation(Duration.ofSeconds(160))
  }

  @nowarn("msg=match may not be exhaustive")
  def advanceTimeAndWaitForRoundAutomation(
      duration: Duration
  )(implicit env: CoinTestConsoleEnvironment) = {
    val (previousOpenRounds, previousIssuingRounds) = scan.getOpenAndIssuingMiningRounds()
    val Seq(lowestOpen, middleOpen, highestOpen) = previousOpenRounds.map(_.payload.round.number)

    // not exactly 150s because of the skew parameter.
    actAndCheck("advancing time", advanceTime(duration))(
      s"waiting for open and issuing round automation (should create OpenMiningRound ${highestOpen + 1}, should advance IssuingMiningRounds ${previousIssuingRounds}",
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

  def getSortedOpenMiningRounds(
      remoteParticipant: CoinRemoteParticipantReference,
      validatorPartyId: PartyId,
  ): Seq[OpenMiningRound.Contract] = remoteParticipant.ledger_api_extensions.acs
    .filterJava(OpenMiningRound.COMPANION)(validatorPartyId)
    .sortBy(_.data.round.number)

  def getSortedIssuingRounds(
      remoteParticipant: CoinRemoteParticipantReference,
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
  )(implicit env: CoinTestConsoleEnvironment) = {
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
  )(implicit env: CoinTestConsoleEnvironment) = {
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
}
