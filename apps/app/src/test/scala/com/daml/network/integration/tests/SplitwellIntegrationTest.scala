package com.daml.network.integration.tests

import com.digitalasset.canton.DomainAlias
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.concurrent.Future

class SplitwellIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwell" should {
    "restart cleanly" in { implicit env =>
      providerSplitwellBackend.stop()
      providerSplitwellBackend.startSync()
    }

    "allocate unique groups per party, even when multiple requests race for them" in {
      implicit env =>
        import env.*

        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

        createSplitwellInstalls(aliceSplitwell, aliceUserParty)

        def createGroup() = {
          val groupRequest = aliceSplitwell.requestGroup("group1")

          // Wait for request to be archived and therefore either the group to be created or
          // the request to be rejected.
          eventually() {
            aliceSplitwell.ledgerApi.ledger_api_extensions.acs
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
          aliceSplitwell.ledgerApi.ledger_api_extensions.acs
            .filterJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)
        groups should have size 1
    }

    "use its own app domain" in { implicit env =>
      val (_, bobUserParty, _, _, key, _) = initSplitwellTest()

      aliceWallet.tap(50)

      val installs = aliceSplitwell.listSplitwellInstalls()
      installs.keySet.map(_.uid.id) shouldBe Set("splitwell")

      val (_, paymentRequest) = actAndCheck(
        "alice initiates transfer on splitwell domain",
        aliceSplitwell.initiateTransfer(
          key,
          Seq(
            new walletCodegen.ReceiverCCAmount(
              bobUserParty.toProtoPrimitive,
              BigDecimal(42.0).bigDecimal,
            )
          ),
        ),
      )(
        "alice sees payment request on global domain",
        _ => aliceWallet.listAppPaymentRequests().headOption.value,
      )

      actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWallet.acceptAppPaymentRequest(paymentRequest.appPaymentRequest.contractId),
      )(
        "alice sees balance update on splitwell domain",
        _ =>
          inside(aliceSplitwell.listBalanceUpdates(key)) { case Seq(update) =>
            aliceValidator.remoteParticipant.transfer
              .lookup_contract_domain(update.contractId) shouldBe Map(
              javaToScalaContractId(update.contractId) -> "splitwell"
            )
          },
      )
    }

    "return the primary party of the user" in { implicit env =>
      val user = providerSplitwellBackend.remoteParticipantWithAdminToken.ledger_api.users
        .get(providerSplitwellBackend.config.providerUser)
      Some(providerSplitwellBackend.getProviderPartyId().toLf) shouldBe user.primaryParty
    }

    "domain disconnect" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      createSplitwellInstalls(aliceSplitwell, alice)
      actAndCheck("alice creates group1", aliceSplitwell.requestGroup("group1"))(
        "alice observes group",
        _ => aliceSplitwell.listGroups() should have size 1,
      )
      try {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          providerSplitwellBackend.remoteParticipant.domains
            .disconnect(DomainAlias.tryCreate("splitwell")),
          logs =>
            logs.filter(
              _.message.contains(
                "Completed processing with outcome: Stopped service for splitwell::"
              )
            ) should have size 5,
        )
      } finally {
        providerSplitwellBackend.remoteParticipant.domains.reconnect(
          DomainAlias.tryCreate("splitwell")
        )
      }
      actAndCheck("alice creates group2", aliceSplitwell.requestGroup("group2"))(
        "alice observes group",
        _ => aliceSplitwell.listGroups() should have size 2,
      )
    }
  }
}
