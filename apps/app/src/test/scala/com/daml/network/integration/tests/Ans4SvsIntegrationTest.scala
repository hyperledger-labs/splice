package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.wallet.subscriptions as subscriptionsCodegen
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.automation.leaderbased.TerminatedSubscriptionTrigger
import com.daml.network.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.Assertion

class Ans4SvsIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TriggerTestUtil {

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://ans-dir-url.com"
  private val testEntryDescription = "Sample CNS Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)

  // TODO (#12697): reenable
  override protected def runUpdateHistorySanityCheck: Boolean = false

  // TODO(#11927): incorporate this test into AnsIntegrationTest
  "ans" should {
    "terminated subscriptions are archived" in { implicit env =>
      val leaderTerminatedSubscriptionTrigger =
        sv1Backend.leaderBasedAutomation.trigger[TerminatedSubscriptionTrigger]

      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      setTriggersWithin[Assertion](
        triggersToPauseAtStart = Seq(),
        triggersToResumeAtStart = Seq(),
      ) {

        val ansRules = sv1ScanBackend.getAnsRules()

        val subReqId = clue("Alice requests a ans entry") {
          val cmd = ansRules.contractId.exerciseAnsRules_RequestEntry(
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            aliceUserParty.toProtoPrimitive,
          )
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceWalletClient.config.ledgerApiUser,
              actAs = Seq(aliceUserParty),
              readAs = Seq.empty,
              update = cmd,
              disclosedContracts = DisclosedContracts(ansRules).toLedgerApiDisclosedContracts,
            )
            .exerciseResult
            .requestCid
        }

        aliceWalletClient.tap(50.0)
        val (_, subscriptionStateId) = actAndCheck(
          "Alice accepts subscription and waits for entry", {
            aliceWalletClient.acceptSubscriptionRequest(subReqId)
          },
        )(
          "Subscription and entry are created",
          _ => {
            aliceWalletClient.listSubscriptions() should have length 1
            inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
              inside(sub.state) { case HttpWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.subscription.contractId)
                state.contractId
              }
            }
          },
        )

        clue("Pausing TerminatedSubscriptionTrigger") {
          leaderTerminatedSubscriptionTrigger.pause().futureValue
        }

        actAndCheck(
          "Cancel the subscription",
          aliceWalletClient.cancelSubscription(subscriptionStateId),
        )(
          "no more subscriptions exist",
          _ => {
            aliceWalletClient.listSubscriptions() shouldBe empty
          },
        )

        eventually() {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(subscriptionsCodegen.TerminatedSubscription.COMPANION)(
              aliceUserParty
            ) should not be empty
        }
        clue("Resuming TerminatedSubscriptionTrigger") {
          leaderTerminatedSubscriptionTrigger.resume()
        }

        eventually() {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(subscriptionsCodegen.TerminatedSubscription.COMPANION)(
              aliceUserParty
            ) shouldBe empty
        }

      }
    }

  }

}
