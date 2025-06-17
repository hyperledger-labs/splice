package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.splitwell.automation.AcceptedAppPaymentRequestsTrigger
import org.lfdecentralizedtrust.splice.util.{SplitwellTestUtil, TriggerTestUtil, WalletTestUtil}

import scala.concurrent.Future
import scala.concurrent.duration.*

class SplitwellIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "splitwell" should {
    "restart cleanly" in { implicit env =>
      splitwellBackend.stop()
      splitwellBackend.startSync()
    }

    "splitwell should report liveness and readiness" in { implicit env =>
      splitwellBackend.httpLive shouldBe true
      splitwellBackend.httpReady shouldBe true
    }

    "allocate unique groups per party, even when multiple requests race for them" in {
      implicit env =>
        import env.*

        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        createSplitwellInstalls(aliceSplitwellClient, aliceUserParty)

        def createGroup() = {
          val groupRequest = aliceSplitwellClient.requestGroup("group1")

          // Wait for request to be archived and therefore either the group to be created or
          // the request to be rejected.
          eventually() {
            aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
              .filterJava(splitwellCodegen.GroupRequest.COMPANION)(
                aliceUserParty,
                (request: splitwellCodegen.GroupRequest.Contract) => request.id == groupRequest,
              ) shouldBe empty
          }
        }

        // Concurrently, create two groups with the same id
        val group1 = Future {
          createGroup()
        }
        val group2 = Future {
          createGroup()
        }

        // Wait for both of them
        group1.futureValue
        group2.futureValue

        // We read directly from the ledger API to avoid having to synchronize on the store.
        val groups =
          aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
            .filterJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)
        groups should have size 1
    }

    "use its own app domain" in { implicit env =>
      val (aliceUserParty, bobUserParty, _, _, key, _) = initSplitwellTest()

      aliceWalletClient.tap(50)

      val rules = aliceSplitwellClient.listSplitwellRules()
      rules.keySet.map(_.uid.identifier) shouldBe Set("splitwell")

      val (_, paymentRequest) =
        actAndCheck(timeUntilSuccess = 40.seconds, maxPollInterval = 1.second)(
          "alice initiates transfer on splitwell domain",
          aliceSplitwellClient.initiateTransfer(
            key,
            Seq(
              new walletCodegen.ReceiverAmuletAmount(
                bobUserParty.toProtoPrimitive,
                BigDecimal(42.0).bigDecimal,
              )
            ),
          ),
        )(
          "alice sees payment request on global domain",
          _ => {
            getSingleAppPaymentRequest(aliceWalletClient)
          },
        )

      actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
      )(
        "alice sees balance update on splitwell domain",
        _ =>
          inside(aliceSplitwellClient.listBalanceUpdates(key)) { case Seq(update) =>
            val synchronizerId = aliceValidatorBackend.participantClient.synchronizers.id_of(
              SynchronizerAlias.tryCreate("splitwell")
            )
            aliceValidatorBackend.participantClient.ledger_api_extensions.acs
              .lookup_contract_domain(
                aliceUserParty,
                Set(update.contractId.contractId),
              ) shouldBe Map(
              update.contractId.contractId -> synchronizerId.logical
            )
          },
      )
    }

    "return the primary party of the user" in { implicit env =>
      val user = splitwellBackend.participantClientWithAdminToken.ledger_api.users
        .get(splitwellBackend.config.providerUser)
      Some(splitwellBackend.getProviderPartyId()) shouldBe user.primaryParty
    }

    "domain disconnect" in { implicit env =>
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      createSplitwellInstalls(aliceSplitwellClient, alice)
      actAndCheck("alice creates group1", aliceSplitwellClient.requestGroup("group1"))(
        "alice observes group",
        _ => aliceSplitwellClient.listGroups() should have size 1,
      )
      try {
        splitwellBackend.participantClient.synchronizers
          .disconnect(SynchronizerAlias.tryCreate("splitwell"))
      } finally {
        splitwellBackend.participantClient.synchronizers.reconnect(
          SynchronizerAlias.tryCreate("splitwell")
        )
      }
      actAndCheck("alice creates group2", aliceSplitwellClient.requestGroup("group2"))(
        "alice observes group",
        _ => aliceSplitwellClient.listGroups() should have size 2,
      )
    }

    "archives stale TransferInProgress contracts" in { implicit env =>
      val (aliceUserParty, bobUserParty, _, _, key, _) = initSplitwellTest()

      aliceWalletClient.tap(50)

      val (paymentRequest, _) =
        actAndCheck(
          "alice initiates transfer on splitwell domain",
          aliceSplitwellClient.initiateTransfer(
            key,
            Seq(
              new walletCodegen.ReceiverAmuletAmount(
                bobUserParty.toProtoPrimitive,
                BigDecimal(42.0).bigDecimal,
              )
            ),
          ),
        )(
          "alice sees payment request",
          _ => aliceWalletClient.listAppPaymentRequests().headOption.value,
        )

      aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
        .filterJava(splitwellCodegen.TransferInProgress.COMPANION)(
          aliceUserParty
        ) should have size 1

      actAndCheck(
        "alice reject payment request",
        aliceWalletClient.rejectAppPaymentRequest(paymentRequest),
      )(
        "splitwell automation archives transfer in progress",
        _ =>
          aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
            .filterJava(splitwellCodegen.TransferInProgress.COMPANION)(
              aliceUserParty
            ) should have size 0,
      )
    }

    "support provider-hosted mode" in { implicit env =>
      val previousListAppRewardCoupons = splitwellWalletClient.listAppRewardCoupons().size
      val (aliceUserParty, bobUserParty, charlieUserParty, _, key, invite) =
        initSplitwellTest()

      aliceSplitwellClient.enterPayment(
        key,
        4200.0,
        "payment",
      )
      bobWalletClient.tap(4000)
      splitwellTransfer(
        bobSplitwellClient,
        bobWalletClient,
        aliceUserParty,
        BigDecimal(1000.0),
        key,
      )

      eventually() {
        bobSplitwellClient.listBalanceUpdates(key) should have size 2
      }
      bobSplitwellClient.listBalances(key) shouldBe Seq(aliceUserParty -> -1100).toMap

      aliceSplitwellClient.listBalanceUpdates(key) should have size 2
      aliceSplitwellClient.listBalances(key) shouldBe Seq(bobUserParty -> 1100).toMap

      charlieSplitwellClient.acceptInvite(invite)

      eventually() {
        aliceSplitwellClient.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwellClient.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwellClient.joinGroup(accepted.contractId)
      }

      eventually() {
        inside(charlieSplitwellClient.listGroups()) { case Seq(singleGroup) =>
          singleGroup.contract.payload.id shouldBe invite.contract.payload.group.id
        }
      }

      charlieSplitwellClient.listBalances(key) shouldBe Map.empty
      charlieSplitwellClient.enterPayment(key, 3300.0, "payment")
      eventually() {
        charlieSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 1100,
          bobUserParty -> 1100,
        )
      }

      eventually()(aliceSplitwellClient.listBalanceUpdates(key) should have size 3)
      aliceSplitwellClient.listBalances(key) shouldBe Map(
        bobUserParty -> 1100,
        charlieUserParty -> -1100,
      )

      aliceSplitwellClient.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -1100, charlieUserParty -> 1100),
          bobUserParty -> Map(aliceUserParty -> 1100, charlieUserParty -> -1100),
          charlieUserParty -> Map(aliceUserParty -> -1100, bobUserParty -> 1100),
        ),
      )
      eventually() {
        aliceSplitwellClient.listBalances(key) shouldBe Map(
          bobUserParty -> 0,
          charlieUserParty -> 0,
        )
        bobSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 0,
          charlieUserParty -> -2200,
        )
        charlieSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 0,
          bobUserParty -> 2200,
        )
        splitwellWalletClient
          .listAppRewardCoupons()
          .size shouldBe previousListAppRewardCoupons + 1
      }
    }

    "be able to collect app payments across round changes" in { implicit env =>
      val (aliceUserParty, bobUserParty, _, _, key, _) =
        initSplitwellTest()

      def splitwellAcceptedAppPaymentRequestsTrigger =
        splitwellBackend.splitwellAutomation
          .trigger[AcceptedAppPaymentRequestsTrigger]

      aliceSplitwellClient.enterPayment(
        key,
        100.0,
        "team lunch",
      )
      bobWalletClient.tap(710)
      clue("Splitwell transfer with round change right after payment request") {

        bobSplitwellClient.initiateTransfer(
          key,
          Seq(
            new walletCodegen.ReceiverAmuletAmount(
              aliceUserParty.toProtoPrimitive,
              BigDecimal(50.0).bigDecimal,
            )
          ),
        )
        val request = eventually()(getSingleAppPaymentRequest(bobWalletClient))
        // to avoid the automation triggering before the round change
        setTriggersWithin(
          triggersToPauseAtStart = Seq(splitwellAcceptedAppPaymentRequestsTrigger),
          triggersToResumeAtStart = Seq(),
        ) {
          bobWalletClient.acceptAppPaymentRequest(request.contractId)
          eventually()(bobWalletClient.listAppPaymentRequests() shouldBe empty)
        }
      }
      eventually() {
        aliceSplitwellClient.listBalanceUpdates(key) should have size 2
      }
      aliceSplitwellClient.listBalances(key) shouldBe Seq(bobUserParty -> 0).toMap
    }
  }
}
