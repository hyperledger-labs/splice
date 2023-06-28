package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{
  Contract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
  TimeTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.Assertion

import java.time.Duration

abstract class AcsStoreDumpExportTimeBasedIntegrationTestBase
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  val packageSignatures =
    ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
  implicit val templateJsonDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  protected def createTestContracts()(implicit env: FixtureParam): Set[String] = {

    // TODO(#6193): also create an `ImportCrate` contract as a test contract, so it's easy to create a test-dump with a Coin, LockedCoin, and ImportCrate

    // Create three test contracts
    val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
    val aliceValidatorParty = aliceValidator.getValidatorPartyId()
    aliceWallet.tap(110.0)
    lockCoins(
      aliceValidator,
      aliceUserParty,
      aliceValidatorParty,
      aliceWallet.list().coins,
      10,
      sv1Scan,
      Duration.ofDays(10),
    )

    onboardWalletUser(bobWallet, bobValidator)
    val id2 = bobWallet.tap(20.0)

    onboardWalletUser(charlieWallet, aliceValidator)
    val id3 = charlieWallet.tap(30.0)

    val aliceUnlockedIds = aliceValidator.participantClient.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(
        aliceUserParty
      )
      .map(co => co.id.contractId)
    val aliceLockedIds = aliceValidator.participantClient.ledger_api_extensions.acs
      .filterJava(cc.coin.LockedCoin.COMPANION)(
        aliceUserParty
      )
      .map(co => co.id.contractId)

    aliceUnlockedIds
      .appendedAll(aliceLockedIds)
      .appendedAll(Seq(id2.contractId, id3.contractId))
      .toSet

  }

  protected def checkDump(
      expectedContractIds: Set[String],
      dump: http.GetAcsStoreDumpResponse,
  ): Assertion = {
    val contracts = dump.contracts

    // check that the coins we tapped are present in the dump
    val coinContracts = contracts.collect(
      Function.unlift(ev => Contract.fromJson(cc.coin.Coin.COMPANION)(ev).toOption)
    )
    val lockedCoinContracts = contracts.collect(
      Function.unlift(ev => Contract.fromJson(cc.coin.LockedCoin.COMPANION)(ev).toOption)
    )
    val actualContractIds =
      coinContracts
        .map(co => co.contractId.contractId)
        .appendedAll(lockedCoinContracts.map(_.contractId.contractId))
        .toSet
    inside((coinContracts, lockedCoinContracts)) { case (_, _) =>
      expectedContractIds shouldBe actualContractIds
    }
  }
}
class AcsStoreDumpExportTimeBasedIntegrationTest
    extends AcsStoreDumpExportTimeBasedIntegrationTestBase {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "sv1" should {
    "produce an ACS store dump via a download from the SvApp admin api" in { implicit env =>
      val testContractIds = createTestContracts()

      eventually() {
        // Note: use eventually to ensure that the SvSvcStore ingests the change
        val dump = sv1.getAcsStoreDump()
        checkDump(testContractIds, dump)
      }
    }
  }
}
