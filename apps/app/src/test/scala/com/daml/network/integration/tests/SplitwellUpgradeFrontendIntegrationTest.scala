package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import CNNodeTests.BracketSynchronous.*
import com.daml.network.util.{FrontendLoginUtil, SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwellUpgradeFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("aliceSplitwell")
    with FrontendLoginUtil
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.dars.upload(darPath)
      })

  "splitwell frontend with upgraded domain" should {
    "create per domain install contracts" in { implicit env =>
      val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      val oldSplitwellDomain = inside(splitwellDomains.others) { case Seq(d) =>
        d
      }

      onboardWalletUser(aliceWallet, aliceValidator)
      val aliceUser = aliceSplitwell.config.ledgerApiUser
      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(3002, aliceUser)
      }

      eventually() {
        aliceSplitwell.listSplitwellInstalls().keys shouldBe Set(oldSplitwellDomain)
      }

      bracket(
        connectSplitwellUpgradeDomain(aliceValidator.participantClient),
        disconnectSplitwellUpgradeDomain(aliceValidator.participantClient),
      ) {
        withFrontEnd("aliceSplitwell") { implicit webDriver =>
          reloadPage()
        }
        eventually() {
          aliceSplitwell.listSplitwellInstalls().keys shouldBe Set(
            oldSplitwellDomain,
            splitwellDomains.preferred,
          )
        }
        // Wait for all install requests to get rejected. Otherwise, we disconnect the user’s participant too soon and
        // the provider’s backend automation times out on the reject call which can break shutdown.
        clue("Install requests get rejected") {
          eventually() {
            val contracts = providerSplitwellBackend.participantClient.ledger_api_extensions.acs
              .filterJava(splitwellCodegen.SplitwellInstallRequest.COMPANION)(
                providerSplitwellBackend.getProviderPartyId()
              )
            contracts shouldBe empty
          }
        }
      }
    }
  }
}
