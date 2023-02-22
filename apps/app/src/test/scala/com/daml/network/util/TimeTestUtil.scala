package com.daml.network.util

import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  ScanAppBackendReference,
  WalletAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.java.cc.api.v1
import java.time.Duration
import scala.annotation.nowarn
import scala.concurrent.duration.*
import com.daml.network.codegen.java.cc.api.v1.coin.{TimeLock, TransferOutput}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TimeTestUtil extends CoinTestCommon {
  this: CommonCoinAppInstanceReferences & WalletTestUtil =>

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
  )(implicit coinEnv: CoinTestConsoleEnvironment): Unit =
    clue(s"Locking $amount coins for $userParty") {
      val coinOpt = coins.find(_.effectiveAmount >= amount)
      val coinRules = scan.getCoinRules()
      val transferContext = scan.getUnfeaturedAppTransferContext()
      val openRound = scan.getLatestOpenMiningRound(svc.remoteParticipant.ledger_api.time.get())

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
      s"waiting for open and issuing round automation (should create OR ${highestOpen + 1}, should advance IR ${previousIssuingRounds}",
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
}
