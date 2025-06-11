package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subscriptionsCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.TerminatedSubscriptionTrigger
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReconcileSequencerConnectionsTrigger
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}
import org.scalatest.Assertion

class Ans4SvsIntegrationTest extends IntegrationTest with WalletTestUtil with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)

  // TODO(#787): incorporate this test into AnsIntegrationTest
  "ans" should {
    "terminated subscriptions are archived" in { implicit env =>
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
          pauseAllDsoDelegateTriggers[TerminatedSubscriptionTrigger]
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
          resumeAllDsoDelegateTriggers[TerminatedSubscriptionTrigger]
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
