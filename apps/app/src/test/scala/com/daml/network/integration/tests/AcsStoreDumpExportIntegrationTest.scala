package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import better.files.File
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.store.AcsStoreDump
import com.daml.network.util.{Contract, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.util.Using

class AcsStoreDumpExportIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "sv1" should {
    "produce an ACS dump" in { implicit env =>
      // Create three test contracts
      onboardWalletUser(aliceWallet, aliceValidator)
      val id1 = aliceWallet.tap(10.0)

      onboardWalletUser(bobWallet, bobValidator)
      val id2 = bobWallet.tap(20.0)

      onboardWalletUser(charlieWallet, aliceValidator)
      val id3 = charlieWallet.tap(30.0)

      eventually() {
        // Note: use eventually to ensure if the propagation to the SvSvcStore has not completed
        val response = sv1.triggerAcsDump()

        val dumpConfig = sv1.config.acsStoreDump.getOrElse(sys.error("no dump config specified"))
        val dumpDir = File(dumpConfig.directory)
        val dumpFile = dumpDir / response.filename
        val readEvents = Using(dumpFile.newInputStream)(AcsStoreDump.readFile).get
        readEvents should have size (response.numEvents.toLong)
        // TODO(#6073): polish: disable all triggers and also test that the offset matches

        // check that the coins we tapped are present in the dump
        val coinContracts = readEvents.collect(
          Function.unlift(ev => Contract.fromCreatedEvent(cc.coin.Coin.COMPANION)(ev))
        )
        inside(coinContracts)(_ =>
          Set(id1, id2, id3) shouldBe coinContracts.map(co => co.contractId).toSet
        )
      }
    }
  }

}
