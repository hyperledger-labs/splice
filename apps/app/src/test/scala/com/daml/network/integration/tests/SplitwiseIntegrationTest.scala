package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.{splitwise => splitwiseCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.Future

class SplitwiseIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  private val darPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "restart cleanly" in { implicit env =>
      providerSplitwiseBackend.stop()
      providerSplitwiseBackend.startSync()
    }

    "allocate unique groups per party, even when multiple requests race for them" in {
      implicit env =>
        import env._

        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

        aliceSplitwise.createInstallRequest()
        aliceSplitwise.ledgerApi.ledger_api.acs
          .awaitJava(splitwiseCodegen.SplitwiseInstall.COMPANION)(aliceUserParty)

        def createGroup() = {
          val groupRequest = aliceSplitwise.requestGroup("group1")

          // Wait for request to be archived and therefore either the group to be created or
          // the request to be rejected.
          eventually() {
            aliceSplitwise.ledgerApi.ledger_api.acs
              .filterJava(splitwiseCodegen.GroupRequest.COMPANION)(
                aliceUserParty,
                (request: splitwiseCodegen.GroupRequest.Contract) => request.id == groupRequest,
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
          aliceSplitwise.ledgerApi.ledger_api.acs
            .filterJava(splitwiseCodegen.Group.COMPANION)(aliceUserParty)
        groups should have size 1
    }

    "return the primary party of the user" in { implicit env =>
      val user = providerSplitwiseBackend.remoteParticipantWithAdminToken.ledger_api.users
        .get(providerSplitwiseBackend.config.providerUser)
      Some(providerSplitwiseBackend.getProviderPartyId().toLf) shouldBe user.primaryParty
    }

    "list one connected domain" in { implicit env =>
      eventually() {
        providerSplitwiseBackend.listConnectedDomains().keySet shouldBe Set("global", "splitwise")
      }
    }
  }
}
