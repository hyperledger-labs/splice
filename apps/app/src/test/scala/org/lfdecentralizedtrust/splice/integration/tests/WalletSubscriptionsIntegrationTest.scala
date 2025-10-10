package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.{
  Subscription,
  SubscriptionRequest,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletSubscriptionsIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "A wallet" should {
    "fail to get a non-existent subscription request" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val nonExistentName = "does not exist"
      val errorString =
        s"HTTP 404 Not Found GET at '/api/validator/v0/wallet/subscription-requests/does%20not%20exist' on 127.0.0.1:5503. Command failed, message: Contract id not found: ContractId(id = $nonExistentName"

      assertThrowsAndLogsCommandFailures(
        aliceWalletClient.getSubscriptionRequest(
          new SubscriptionRequest.ContractId(nonExistentName)
        ),
        _.errorMessage should include(errorString),
      )
    }

    "allow a user to get, list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      aliceWalletClient.listSubscriptionRequests() shouldBe empty

      val description = "this will be rejected"
      val request = createSelfSubscriptionRequest(
        aliceValidatorBackend.participantClientWithAdminToken,
        aliceWalletClient.config.ledgerApiUser,
        aliceUserParty,
        description = description,
      )

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
      }

      clue("Get the subscription request") {
        aliceWalletClient
          .getSubscriptionRequest(requestId)
          .payload shouldBe request
      }

      actAndCheck(
        "Alice rejects the subscription request",
        aliceWalletClient.rejectSubscriptionRequest(requestId),
      )(
        "alice sees empty list of subscription requests",
        _ => aliceWalletClient.listSubscriptionRequests() shouldBe empty,
      )
    }

    // We put all of this in one test because assembling valid subscription instances
    // is cumbersome and it's easier to just reuse the results of the "accept" flow.
    "allow a user to list and accept subscription requests, " +
      "to list idle subscriptions, to initiate subscription payments, " +
      "and to cancel a subscription" in { implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        aliceWalletClient.listSubscriptionRequests() shouldBe empty
        aliceWalletClient.listSubscriptions() shouldBe empty

        val description = "this will be accepted"
        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(
            aliceValidatorBackend.participantClientWithAdminToken,
            aliceWalletClient.config.ledgerApiUser,
            aliceUserParty,
            paymentInterval = Duration.ofMinutes(10),
            paymentDuration = Duration.ofMinutes(10),
            description = description,
          ),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
              r.payload shouldBe request
              r.contractId
            },
        )
        clue("Alice gets some amulets") {
          aliceWalletClient.tap(50)
        }

        val (initialPaymentId, initialPaymentRound) = actAndCheck(
          "Accept the subscription request, which initiates the first subscription payment",
          aliceWalletClient.acceptSubscriptionRequest(requestId),
        )(
          "initial subscription payment is listed correctly",
          initialPaymentId => {
            aliceWalletClient.listSubscriptionRequests() shouldBe empty
            inside(aliceWalletClient.listSubscriptionInitialPayments()) { case Seq(r) =>
              r.contractId shouldBe initialPaymentId
              r.payload.subscriptionData should equal(request.subscriptionData)
              r.payload.payData should equal(request.payData)
              r.payload.round
            }
          },
        )

        val (_, (paymentId, paymentRound)) = actAndCheck(
          "Collect the initial payment (as the receiver), which creates the subscription", {
            val transferContext = sv1ScanBackend.getTransferContextWithInstances(
              CantonTimestamp.now(),
              Some(initialPaymentRound),
            )
            val appTransferContext = transferContext.toUnfeaturedAppTransferContext()
            val roundContract = transferContext.openMiningRounds
              .find(c => c.contract.payload.round == initialPaymentRound)
              .value
            val collectCommand = initialPaymentId
              .exerciseSubscriptionInitialPayment_Collect(appTransferContext)
              .commands
              .asScala
              .toSeq
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                commands = collectCommand,
                disclosedContracts = DisclosedContracts
                  .forTesting(
                    transferContext.amuletRules,
                    roundContract,
                  )
                  .toLedgerApiDisclosedContracts,
              )
          },
        )(
          // note that because this test sets paymentDuration = paymentInterval,
          // the wallet backend can make the second payment immediately
          "an automated subscription payment is eventually initiated by the wallet",
          _ =>
            inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
              sub.subscription.payload should equal(
                new Subscription(request.subscriptionData, requestId)
              )
              inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.subscription.contractId
                state.payload.payData should equal(request.payData)
                (state.contractId, state.payload.round)
              }
            },
        )

        val (_, subscriptionStateId2) = actAndCheck(
          "Collect the second payment (as the receiver), which sets the subscription back to idle", {
            val transferContext =
              sv1ScanBackend.getTransferContextWithInstances(
                CantonTimestamp.now(),
                Some(paymentRound),
              )
            val appTransferContext = transferContext.toUnfeaturedAppTransferContext()
            val roundContract = transferContext.openMiningRounds
              .find(c => c.contract.payload.round == paymentRound)
              .value
            val collectCommand2 = paymentId
              .exerciseSubscriptionPayment_Collect(appTransferContext)
              .commands
              .asScala
              .toSeq
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                commands = collectCommand2,
                disclosedContracts = DisclosedContracts
                  .forTesting(
                    transferContext.amuletRules,
                    roundContract,
                  )
                  .toLedgerApiDisclosedContracts,
              )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
              sub.subscription.payload should equal(
                new Subscription(request.subscriptionData, requestId)
              )
              inside(sub.state) { case HttpWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.subscription.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        actAndCheck(
          "Cancel the subscription",
          aliceWalletClient.cancelSubscription(subscriptionStateId2),
        )("no more subscriptions exist", _ => aliceWalletClient.listSubscriptions() shouldBe empty)
      }
  }
}
