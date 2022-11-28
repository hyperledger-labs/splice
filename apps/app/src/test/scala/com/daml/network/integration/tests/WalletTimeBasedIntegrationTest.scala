package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.CoinTestUtil
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration
import scala.util.{Success, Try}

class WalletTimeBasedIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "A wallet" should {

    "allow a user to list multiple subscriptions in different states" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      clue("Alice gets some coins") {
        aliceRemoteWallet.tap(50)
      }
      clue("Setting up directory as provider for the created subscriptions") {
        val directoryDarPath = "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.remoteParticipant.ledger_api.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }
      aliceRemoteWallet.listSubscriptions() shouldBe empty

      clue("Creating 3 subscriptions, 10 days apart") {
        for ((name, i) <- List("alice1", "alice2", "alice3").zipWithIndex) {

          val (_, requestId) = actAndCheck(
            "Request directory entry", {
              aliceDirectory.requestDirectoryEntryWithSubscription(name)._1
            },
          )(
            "the corresponding subscription request is created",
            { _ =>
              inside(aliceRemoteWallet.listSubscriptionRequests()) { case Seq(r) => r.contractId }
            },
          )
          actAndCheck(
            "Accept subscription request", {
              aliceRemoteWallet.acceptSubscriptionRequest(requestId)
            },
          )(
            "subscription is created and no subscription is ready for payment",
            _ => {
              val subs = aliceRemoteWallet.listSubscriptions()
              val now = svc.remoteParticipant.ledger_api.time.get()
              subs.length shouldBe i + 1
              // TODO(#1217) we can remove this check once `renewalDuration == entryLifetime` is no longer hard-coded in the directory backend
              subs.foreach(sub => {
                sub.state match {
                  case GrpcWalletAppClient.SubscriptionIdleState(state) =>
                    assert(state.payload.nextPaymentDueAt.isAfter(now.toInstant))
                  case _ => fail()
                }
              })
            },
          )
          advanceTime(Duration.ofDays(10))
        }
      }
      clue("Stopping directory backend so that payments aren't collected.") {
        directory.stop()
      }
      actAndCheck(
        "Wait for the time for a payment on the first subscription to arrive",
        advanceTime(Duration.ofDays(60)),
      )(
        "2 idle subscriptions and 1 payment are listed",
        _ => {
          eventually() {
            val subs = aliceRemoteWallet.listSubscriptions()
            subs.length shouldBe 3
            subs
              .count(_.state match {
                case GrpcWalletAppClient.SubscriptionIdleState(_) => true
                case _ => false
              }) shouldBe 2
            subs
              .count(_.state match {
                case GrpcWalletAppClient.SubscriptionPayment(_) => true
                case _ => false
              }) shouldBe 1
          }
        },
      )
    }
  }

  "automatically collect app & validator rewards on coin operations" in { implicit env =>
    val (aliceUserParty, bobUserParty) = setupAliceAndBobAndChannel(this)
    // Set-up payment channel between alice and her validator
    val proposalId =
      aliceValidatorRemoteWallet.proposePaymentChannel(
        aliceUserParty,
        senderTransferFeeRatio = 0.5,
      )
    eventually()(aliceRemoteWallet.listPaymentChannelProposals() should have size 1)
    aliceRemoteWallet.acceptPaymentChannelProposal(proposalId)
    eventually()(aliceValidatorRemoteWallet.listPaymentChannels() should have size 1)

    aliceRemoteWallet.tap(50)
    aliceValidatorRemoteWallet.tap(50)
    eventually()(aliceRemoteWallet.list().coins should have size 1)

    // Execute a transfer in round -> leads to rewards being generated
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 40)
    eventually()(aliceRemoteWallet.listAppRewards() should have size 1)
    eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 1)

    // next round.
    svc.openRound(1, 1)
    svc.startSummarizingRound(0)
    eventually() {
      // automation archives the summarizing round and creates the issuing round
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }
    // ensure issuing round is open
    advanceTime(Duration.ofMinutes(3))
    aliceWallet.remoteParticipant.ledger_api.acs
      .awaitJava(roundCodegen.IssuingMiningRound.COMPANION)(aliceValidator.getValidatorPartyId())

    // alice uses her reward
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 1)
    eventually()(aliceRemoteWallet.listAppRewards() should have size 0)

    // 2 validator rewards due to two transfers
    eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 2)
    aliceValidatorRemoteWallet.executeDirectTransfer(aliceUserParty, 1)
    // +1 for the transfer, -1 due to the reward from round 0 being used
    eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 2 + 1 - 1)
    // no rewards are used, since all other rewards are from round 1
    aliceValidatorRemoteWallet.executeDirectTransfer(aliceUserParty, 1)
    eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 3)

  }

  "list and manually collect app & validator rewards" in { implicit env =>
    val (aliceUserParty, bobUserParty) = setupAliceAndBobAndChannel(this)

    // Tap coin and do a transfer from alice to bob
    aliceRemoteWallet.tap(50)
    eventually()(aliceRemoteWallet.list().coins should have size 1)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 40)

    // Retrieve transferred coin in bob's wallet and transfer part of it back to alice; bob will receive some app rewards
    eventually()(bobRemoteWallet.list().coins should have size 1)
    bobRemoteWallet.executeDirectTransfer(aliceUserParty, 30)

    // Wait for app rewards to become visible in bob's wallet, and check structure
    bobWallet.remoteParticipant.ledger_api.acs
      .awaitJava(coinCodegen.AppReward.COMPANION)(bobUserParty)
      .id
    val appRewards = bobRemoteWallet.listAppRewards()
    appRewards should have size 1
    bobRemoteWallet.listValidatorRewards() shouldBe empty

    // Wait for validator rewards to become visible in alice's wallet, check structure
    val validatorRewards = aliceValidatorRemoteWallet.listValidatorRewards()
    validatorRewards should have size 1
    aliceRemoteWallet.tap(200)
    eventually()(aliceRemoteWallet.list().coins should have size 3)

    // Bob collects/realizes rewards
    val prevCoins = bobRemoteWallet.list().coins
    svc.openRound(1, 1)
    svc.startSummarizingRound(0)
    eventually() {
      // automation archives the summarizing round and creates the issuing round
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }
    // ensure issuing round is open
    advanceTime(Duration.ofMinutes(3))
    eventually() {
      val r = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        Try(bobRemoteWallet.collectRewards(0)),
        entries => forAll(entries)(_.message should include("No issuing mining round found")),
      )
      inside(r) { case Success(_) => }
    }
    bobRemoteWallet.listValidatorRewards() shouldBe empty
    // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
    // for rewards.
    checkWallet(
      bobUserParty,
      bobRemoteWallet,
      prevCoins
        .map(c =>
          (
            BigDecimal(c.contract.payload.quantity.initialQuantity),
            BigDecimal(c.contract.payload.quantity.initialQuantity) + 2,
          )
        )
        .sortBy(_._1),
    )
  }
}
