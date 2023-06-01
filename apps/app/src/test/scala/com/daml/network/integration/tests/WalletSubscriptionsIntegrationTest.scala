package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionRequest
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{DisclosedContracts, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletSubscriptionsIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  "A wallet" should {
    "fail to get a non-existent subscription request" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      val nonExistentName = "does not exist"
      val errorString =
        s"status: 404 Not Found, message: contract id not found: ContractId(id = $nonExistentName"

      assertThrowsAndLogsCommandFailures(
        aliceWallet.getSubscriptionRequest(new SubscriptionRequest.ContractId(nonExistentName)),
        _.errorMessage should include(errorString),
      )
    }

    "allow a user to get, list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceWallet.listSubscriptionRequests() shouldBe empty

      val description = "this will be rejected"
      val request = createSelfSubscriptionRequest(
        aliceValidator.participantClientWithAdminToken,
        aliceWallet.config.ledgerApiUser,
        aliceUserParty,
        description = description,
      )

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.subscriptionRequest.payload shouldBe request
            r.context.payload.description shouldBe description
            r.subscriptionRequest.contractId
          }
        }
      }

      clue("Get the subscription request") {
        aliceWallet.getSubscriptionRequest(requestId).subscriptionRequest.payload shouldBe request
      }

      actAndCheck(
        "Alice rejects the subscription request",
        aliceWallet.rejectSubscriptionRequest(requestId),
      )(
        "alice sees empty list of subscription requests",
        _ => aliceWallet.listSubscriptionRequests() shouldBe empty,
      )
    }

    // We put all of this in one test because assembling valid subscription instances
    // is cumbersome and it's easier to just reuse the results of the "accept" flow.
    "allow a user to list and accept subscription requests, " +
      "to list idle subscriptions, to initiate subscription payments, " +
      "and to cancel a subscription" in { implicit env =>
        val transferContext = scan.getTransferContextWithInstances(CantonTimestamp.now())
        val appTransferContext = transferContext.toUnfeaturedAppTransferContext()
        val openRound = transferContext.latestOpenMiningRound
        val coinRules = transferContext.coinRules
        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceWallet.listSubscriptionRequests() shouldBe empty
        aliceWallet.listSubscriptions() shouldBe empty

        val description = "this will be accepted"
        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(
            aliceValidator.participantClientWithAdminToken,
            aliceWallet.config.ledgerApiUser,
            aliceUserParty,
            paymentInterval = Duration.ofMinutes(10),
            paymentDuration = Duration.ofMinutes(10),
            description = description,
          ),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
              r.subscriptionRequest.payload shouldBe request
              r.context.payload.description shouldBe description
              r.subscriptionRequest.contractId
            },
        )
        clue("Alice gets some coins") {
          aliceWallet.tap(50)
        }

        val (initialPaymentId, _) = actAndCheck(
          "Accept the subscription request, which initiates the first subscription payment",
          aliceWallet.acceptSubscriptionRequest(requestId),
        )(
          "initial subscription payment is listed correctly",
          initialPaymentId => {
            aliceWallet.listSubscriptionRequests() shouldBe empty
            inside(aliceWallet.listSubscriptionInitialPayments()) { case Seq(r) =>
              r.contractId shouldBe initialPaymentId
              r.payload.subscriptionData should equal(request.subscriptionData)
              r.payload.payData should equal(request.payData)
            }
          },
        )

        val (_, paymentId) = actAndCheck(
          "Collect the initial payment (as the receiver), which creates the subscription", {
            val collectCommand = initialPaymentId
              .exerciseSubscriptionInitialPayment_Collect(appTransferContext)
              .commands
              .asScala
              .toSeq
            aliceValidator.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                optTimeout = None,
                commands = collectCommand,
                disclosedContracts =
                  DisclosedContracts(coinRules, openRound).toLedgerApiDisclosedContracts,
              )
          },
        )(
          // note that because this test sets paymentDuration = paymentInterval,
          // the wallet backend can make the second payment immediately
          "an automated subscription payment is eventually initiated by the wallet",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.subscription.payload should equal(request.subscriptionData)
              inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.subscription.contractId
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        val (_, subscriptionStateId2) = actAndCheck(
          "Collect the second payment (as the receiver), which sets the subscription back to idle", {
            val collectCommand2 = paymentId
              .exerciseSubscriptionPayment_Collect(appTransferContext)
              .commands
              .asScala
              .toSeq
            aliceValidator.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                optTimeout = None,
                commands = collectCommand2,
                disclosedContracts =
                  DisclosedContracts(coinRules, openRound).toLedgerApiDisclosedContracts,
              )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.subscription.payload should equal(request.subscriptionData)
              inside(sub.state) { case HttpWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.subscription.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        actAndCheck(
          "Cancel the subscription",
          aliceWallet.cancelSubscription(subscriptionStateId2),
        )("no more subscriptions exist", _ => aliceWallet.listSubscriptions() shouldBe empty)
      }
  }
}
