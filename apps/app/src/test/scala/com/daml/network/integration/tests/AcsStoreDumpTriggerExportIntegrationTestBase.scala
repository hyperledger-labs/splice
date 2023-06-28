package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc
import com.daml.network.config.{CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.sv.config.SvAcsStoreDumpConfig
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.{
  Contract,
  GcpBucket,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.file.Paths
import java.nio.charset.StandardCharsets

abstract class AcsStoreDumpTriggerExportIntegrationTestBase[T <: SvAcsStoreDumpConfig]
    extends CNNodeIntegrationTest
    with WalletTestUtil {

  protected def acsStoreDumpConfig: T

  protected def readDump(filename: String): String

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        (_, conf) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs_(c =>
            c.copy(acsStoreDump = Some(acsStoreDumpConfig))
          )(conf),
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
    "produce an ACS store dump via triggering the writing to a file" in { implicit env =>
      val testContractIds = createTestContracts()

      eventually() {
        // Note: use eventually to ensure if the propagation to the SvSvcStore has not completed
        val response = sv1.triggerAcsDump()

        val dump = readDump(response.filename)

        val jsonDump = io.circe.parser
          .decode[http.GetAcsStoreDumpResponse](dump)
          .fold(
            err => throw new IllegalArgumentException(s"Failed to parse dump: $err"),
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

final class DirectoryAcsStoreDumpTriggerExportIntegrationTest
    extends AcsStoreDumpTriggerExportIntegrationTestBase[SvAcsStoreDumpConfig.Directory] {
  override def acsStoreDumpConfig = SvAcsStoreDumpConfig.Directory(Paths.get("dumps/testing"))

  override def readDump(filename: String) = {
    import better.files.File
    val dumpDir = File(acsStoreDumpConfig.directory)
    val dumpFile = dumpDir / filename
    dumpFile.contentAsString
  }

}

final class GcpBucketAcsStoreDumpTriggerExportIntegrationTest
    extends AcsStoreDumpTriggerExportIntegrationTestBase[SvAcsStoreDumpConfig.Gcp] {
  override def acsStoreDumpConfig = SvAcsStoreDumpConfig.Gcp(GcpBucketConfig.inferForTesting)
  val bucket = new GcpBucket(acsStoreDumpConfig.bucket, loggerFactory)
  override def readDump(filename: String) = {
    new String(bucket.readBytesFromBucket(filename), StandardCharsets.UTF_8)
  }
}
