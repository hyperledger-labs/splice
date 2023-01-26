package com.daml.network.integration.tests

import com.daml.network.config.CoinConfigTransforms
import com.daml.network.codegen.java.cn.{splitwise => splitwiseCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwiseMultiDomainIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  private val darPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CoinConfigTransforms.useSeparateSplitwiseDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "go through install flow on private domain" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceSplitwise.createInstallRequest()
      val install = aliceSplitwise.ledgerApi.ledger_api.acs
        .awaitJava(splitwiseCodegen.SplitwiseInstall.COMPANION)(aliceUserParty)
      val domains = aliceValidator.remoteParticipantWithAdminToken.transfer
        .lookup_contract_domain(install.id)
      domains shouldBe Map(
        javaToScalaContractId(install.id) -> "splitwise"
      )
      actAndCheck("Request group", aliceSplitwise.requestGroup("group1"))(
        "Wait for group to be created",
        _ => aliceSplitwise.listGroups() should have size 1,
      )
    }
  }
}
