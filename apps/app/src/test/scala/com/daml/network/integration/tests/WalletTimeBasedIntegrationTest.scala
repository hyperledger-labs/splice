package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.{directory => dirCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration

class WalletTimeBasedIntegrationTest extends CoinIntegrationTest {

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
          val (_, requestId) = aliceDirectory.requestDirectoryEntryWithSubscription(name)
          aliceRemoteWallet.acceptSubscriptionRequest(requestId)
          eventually() {
            val subs = aliceRemoteWallet.listSubscriptions()
            val now = svc.remoteParticipant.ledger_api.time.get()
            subs.length shouldBe i + 1
            // TODO(i1217) we can remove this check once `renewalDuration == entryLifetime` is no longer hard-coded in the directory backend
            subs.foreach(sub => {
              sub.state match {
                case GrpcWalletAppClient.SubscriptionIdleState(state) =>
                  assert(state.payload.nextPaymentDueAt.isAfter(now.toInstant))
                case _ => fail()
              }
            })
          }
          advanceTime(Duration.ofDays(10))
        }
      }
      clue("Stopping directory backend so that payments aren't collected.") {
        directory.stop()
      }
      clue("Waiting for the time for a payment on the first subscription to arrive...") {
        advanceTime(Duration.ofDays(60))
      }
      clue("Checking that 2 idle subscriptions and 1 payment are listed.") {
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
      }
    }

  }
}
