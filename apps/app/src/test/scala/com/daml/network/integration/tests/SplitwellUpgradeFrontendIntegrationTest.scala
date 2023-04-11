package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import CNNodeTests.BracketSynchronous.*
import com.daml.network.util.{FrontendLoginUtil, WalletTestUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

class SplitwellUpgradeFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("aliceSplitwell")
    with FrontendLoginUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
      })

  private val splitwellUpgradeAlias = DomainAlias.tryCreate("splitwellUpgrade")

//  private val splitwellAlias = DomainAlias.tryCreate("splitwell")

  "splitwell frontend with upgraded domain" should {
    "create per domain install contracts" in { implicit env =>
      val upgradeConfig =
        providerSplitwellBackend.remoteParticipant.domains.config(splitwellUpgradeAlias).value

      val url = upgradeConfig.sequencerConnection
        .asInstanceOf[GrpcSequencerConnection]
        .endpoints
        .head
        .toURI(false)
        .toString

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
        aliceValidator.remoteParticipant.domains.connect(splitwellUpgradeAlias, url),
        aliceValidator.remoteParticipant.domains.disconnect(splitwellUpgradeAlias),
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
            val contracts = providerSplitwellBackend.remoteParticipant.ledger_api_extensions.acs
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
