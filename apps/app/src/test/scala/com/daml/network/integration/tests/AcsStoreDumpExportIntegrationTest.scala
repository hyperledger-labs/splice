package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.{
  Contract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class AcsStoreDumpExportIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  val packageSignatures =
    ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
  implicit val templateJsonDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  private def createTestContracts()(implicit env: FixtureParam): Set[ContractId[cc.coin.Coin]] = {
    // Create three test contracts
    onboardWalletUser(aliceWallet, aliceValidator)
    val id1 = aliceWallet.tap(10.0)

    onboardWalletUser(bobWallet, bobValidator)
    val id2 = bobWallet.tap(20.0)

    onboardWalletUser(charlieWallet, aliceValidator)
    val id3 = charlieWallet.tap(30.0)
    Set(id1, id2, id3)
  }

  "sv1" should {
    "produce an ACS store dump via a download from the SvApp admin api" in { implicit env =>
      val testContractIds = createTestContracts()

      eventually() {
        // Note: use eventually to ensure that the SvSvcStore ingests the change
        val dump = sv1.getAcsStoreDump()
        val contracts = dump.contracts

        // check that the coins we tapped are present in the dump
        val coinContracts = contracts.collect(
          Function.unlift(ev => Contract.fromJson(cc.coin.Coin.COMPANION)(ev).toOption)
        )
        inside(coinContracts)(_ =>
          testContractIds shouldBe coinContracts.map(co => co.contractId).toSet
        )
      }
    }

    "produce an ACS store dump via triggering the writing to a file" in { implicit env =>
      val testContractIds = createTestContracts()

      eventually() {
        import better.files.File

        // Note: use eventually to ensure if the propagation to the SvSvcStore has not completed
        val response = sv1.triggerAcsDump()

        val dumpConfig = sv1.config.acsStoreDump.getOrElse(sys.error("no dump config specified"))
        val dumpDir = File(dumpConfig.directory)
        val dumpFile = dumpDir / response.filename

        val jsonDump = io.circe.parser
          .decode[http.GetAcsStoreDumpResponse](dumpFile.contentAsString)
          .fold(
            err => throw new IllegalArgumentException(s"Failed to parse $dumpFile: $err"),
            result => result,
          )
        val contracts = jsonDump.contracts
        contracts should have size (response.numEvents.toLong)
        // TODO(#6073): polish: disable all triggers and also test that the offset matches

        // check that the coins we tapped are present in the dump
        val coinContracts = contracts.collect(
          Function.unlift(ev => Contract.fromJson(cc.coin.Coin.COMPANION)(ev).toOption)
        )
        inside(coinContracts)(_ =>
          testContractIds shouldBe coinContracts.map(co => co.contractId).toSet
        )
      }
    }
  }

}
