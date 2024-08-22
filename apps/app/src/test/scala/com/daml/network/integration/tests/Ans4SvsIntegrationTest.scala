package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.wallet.subscriptions as subscriptionsCodegen
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.sv.automation.delegatebased.TerminatedSubscriptionTrigger
import com.daml.network.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.ReconcileSequencerConnectionsTrigger
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.Assertion

class Ans4SvsIntegrationTest extends IntegrationTest with WalletTestUtil with TriggerTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)

  // TODO(#11927): incorporate this test into AnsIntegrationTest
  "ans" should {
    "terminated subscriptions are archived" in { implicit env =>
      val leaderTerminatedSubscriptionTrigger =
        sv1Backend.dsoDelegateBasedAutomation.trigger[TerminatedSubscriptionTrigger]

      setTriggersWithin[Assertion](
        // Figure out how to make the `onboardUser` part of `onboardWalletUser` not time out
        // in the even of an untimely domain disconnect
        triggersToPauseAtStart = Seq(
          aliceValidatorBackend.validatorAutomation.trigger[ReconcileSequencerConnectionsTrigger]
        ),
        triggersToResumeAtStart = Seq(),
      ) {
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

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
              disclosedContracts =
                DisclosedContracts.forTesting(ansRules).toLedgerApiDisclosedContracts,
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
