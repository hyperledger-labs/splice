package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletSubscriptionsIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  "A wallet" should {
    "allow a user to list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceWallet.listSubscriptionRequests() shouldBe empty

      val request = createSelfSubscriptionRequest(
        aliceWalletBackend.remoteParticipantWithAdminToken,
        aliceWallet.config.ledgerApiUser,
        aliceUserParty,
      );

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
      }
      clue("Reject the subscription request") {
        aliceWallet.rejectSubscriptionRequest(requestId)
        aliceWallet.listSubscriptionRequests() shouldBe empty
      }
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

        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(
            aliceWalletBackend.remoteParticipantWithAdminToken,
            aliceWallet.config.ledgerApiUser,
            aliceUserParty,
            paymentInterval = Duration.ofMinutes(10),
            paymentDuration = Duration.ofMinutes(10),
          ),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
              r.payload shouldBe request
              r.contractId
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
            aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                optTimeout = None,
                commands = collectCommand,
                disclosedContracts =
                  Seq(coinRules.toDisclosedContract, openRound.toDisclosedContract),
              )
          },
        )(
          // note that because this test sets paymentDuration = paymentInterval,
          // the wallet backend can make the second payment immediately
          "an automated subscription payment is eventually initiated by the wallet",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.main.contractId
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
            aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                readAs = Seq(aliceValidatorParty),
                optTimeout = None,
                commands = collectCommand2,
                disclosedContracts =
                  Seq(coinRules.toDisclosedContract, openRound.toDisclosedContract),
              )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case HttpWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.main.contractId)
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
