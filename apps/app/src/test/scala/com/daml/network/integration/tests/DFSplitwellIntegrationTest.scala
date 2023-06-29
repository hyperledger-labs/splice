package com.daml.network.integration.tests

import com.digitalasset.canton.DomainAlias
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.Future
import scala.concurrent.duration.*

class DFSplitwellIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withTrafficTopupsEnabled
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
      })

  "splitwell" should {
    "restart cleanly" in { implicit env =>
      splitwellBackend.stop()
      splitwellBackend.startSync()
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
      val (_, bobUserParty, _, _, key, _) = initSplitwellTest()

      aliceWalletClient.tap(50)

      val installs = aliceSplitwellClient.listSplitwellInstalls()
      installs.keySet.map(_.uid.id) shouldBe Set("splitwell")

      val (_, paymentRequest) =
        actAndCheck(timeUntilSuccess = 40.seconds, maxPollInterval = 1.second)(
          "alice initiates transfer on splitwell domain",
          aliceSplitwellClient.initiateTransfer(
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
          _ => aliceWalletClient.listAppPaymentRequests().headOption.value,
        )

      actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWalletClient.acceptAppPaymentRequest(paymentRequest.appPaymentRequest.contractId),
      )(
        "alice sees balance update on splitwell domain",
        _ =>
          inside(aliceSplitwellClient.listBalanceUpdates(key)) { case Seq(update) =>
            aliceValidatorBackend.participantClient.transfer
              .lookup_contract_domain(update.contractId) shouldBe Map(
              javaToScalaContractId(update.contractId) -> "splitwell"
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
        splitwellBackend.participantClient.domains
          .disconnect(DomainAlias.tryCreate("splitwell"))
      } finally {
        splitwellBackend.participantClient.domains.reconnect(
          DomainAlias.tryCreate("splitwell")
        )
      }
      actAndCheck("alice creates group2", aliceSplitwellClient.requestGroup("group2"))(
        "alice observes group",
        _ => aliceSplitwellClient.listGroups() should have size 2,
      )
    }
  }
}
