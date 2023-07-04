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
import com.daml.network.store.AcsStoreDump
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

  protected def createTestContracts()(implicit env: FixtureParam): (Set[String], Set[String]) = {
    clue(" Advance by one round, so we can check that we properly restore open mining rounds") {
      advanceRoundsByOneTick
      val openMiningRounds = sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(cc.round.OpenMiningRound.COMPANION)(
          svcParty
        )
      inside(openMiningRounds) { _ =>
        openMiningRounds.map(_.data.round.number).sorted shouldBe Seq(1, 2, 3)
      }
    }

    val aliceUserParty = clue("Create a normal and one locked coin for alice") {
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(110.0)
      lockCoins(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().coins,
        10,
        sv1ScanBackend,
        Duration.ofDays(10),
      )
      aliceUserParty
    }

    val (id2, id3, charlieUserParty) = clue("Tap a coin each for bob and charlie") {
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val id2 = bobWalletClient.tap(20.0)

      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val id3 = charlieWalletClient.tap(30.0)
      (id2, id3, charlieUserParty)
    }

    val charlieCrateId = clue("Create an ImportCrate as a copy of Charlie's coin") {
      val charlieCoin = aliceValidatorBackend.participantClient.ledger_api_extensions.acs
        .awaitJava(cc.coin.Coin.COMPANION)(
          charlieUserParty
        )
      val created = sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        readAs = Seq.empty,
        update = new cc.coinimport.ImportCrate(
          svcParty.toProtoPrimitive,
          AcsStoreDump.dropPartyNameSuffix(charlieUserParty.toProtoPrimitive),
          false,
          charlieCoin.data,
        ).create,
      )
      created.contractId.contractId
    }

    val aliceUnlockedIds = aliceValidatorBackend.participantClient.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(
        aliceUserParty
      )
      .map(co => co.id.contractId)
    val aliceLockedIds = aliceValidatorBackend.participantClient.ledger_api_extensions.acs
      .filterJava(cc.coin.LockedCoin.COMPANION)(
        aliceUserParty
      )
      .map(co => co.id.contractId)

    val aliceRewardContractsIds: Set[String] =
      clue("Check that the locking created app and validator rewards for alice") {
        val aliceAppRewards = aliceValidatorBackend.participantClient.ledger_api_extensions.acs
          .filterJava(cc.coin.AppRewardCoupon.COMPANION)(
            aliceUserParty
          )
        aliceAppRewards should not be empty
        val aliceValidatorRewards =
          aliceValidatorBackend.participantClient.ledger_api_extensions.acs
            .filterJava(cc.coin.ValidatorRewardCoupon.COMPANION)(
              aliceUserParty
            )
        aliceValidatorRewards should not be empty
        aliceAppRewards
          .map(_.id.contractId)
          .appendedAll(
            aliceValidatorRewards.map(_.id.contractId)
          )
          .toSet
      }

    (
      aliceUnlockedIds
        .appendedAll(aliceLockedIds)
        .appendedAll(Seq(id2.contractId, id3.contractId, charlieCrateId))
        .toSet,
      aliceRewardContractsIds,
    )

  }

  protected def checkDump(
      testContractIds: (Set[String], Set[String]),
      dump: http.GetAcsStoreDumpResponse,
  )(implicit env: CNNodeTestConsoleEnvironment): Assertion = {
    val (expectedContractIds, ignoredContractIds) = testContractIds
    val contracts = dump.contracts
    val sv1Party = sv1Backend.getSvcInfo().svParty

    suppressFailedClues(loggerFactory) {
      clue("check that the ignored contracts are not present in the dump") {
        forAll(dump.contracts)(co => ignoredContractIds should not contain (co.contractId))
      }

      val coinContracts = contracts.collect(
        Function.unlift(ev => Contract.fromJson(cc.coin.Coin.COMPANION)(ev).toOption)
      )
      clue("check that the coins we tapped are present in the dump") {
        coinContracts.filter(co =>
          !(expectedContractIds.contains(co.contractId.contractId) ||
            // There is one extra coin in the dump: the SV reward for sv1
            co.payload.owner == sv1Party.toProtoPrimitive)
        ) shouldBe empty
      }

      val lockedCoinContracts = contracts.collect(
        Function.unlift(ev => Contract.fromJson(cc.coin.LockedCoin.COMPANION)(ev).toOption)
      )
      clue("check that the locked coins are present in the dump") {
        lockedCoinContracts.filter(co =>
          !expectedContractIds.contains(co.contractId.contractId)
        ) shouldBe empty
      }

      val importCreateContracts = contracts.collect(
        Function.unlift(ev => Contract.fromJson(cc.coinimport.ImportCrate.COMPANION)(ev).toOption)
      )
      clue("check that the import crate is present in the dump") {
        importCreateContracts.filter(co =>
          !expectedContractIds.contains(co.contractId.contractId)
        ) shouldBe empty
      }

      clue("check that all expected contract-ids are present") {
        val actualContractIds =
          coinContracts
            .map(co => co.contractId.contractId)
            .appendedAll(lockedCoinContracts.map(_.contractId.contractId))
            .appendedAll(importCreateContracts.map(_.contractId.contractId))
            .toSet
        expectedContractIds.diff(actualContractIds) shouldBe empty
      }
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

      val dump = eventually() {
        // Note: use eventually to ensure that the SvSvcStore ingests the change
        val dump = sv1Backend.getAcsStoreDump()
        checkDump(testContractIds, dump)
        dump
      }

      clue("check that the open rounds are present") {
        val openRounds = dump.contracts.collect(
          Function.unlift(ev => Contract.fromJson(cc.round.OpenMiningRound.COMPANION)(ev).toOption)
        )
        inside(openRounds) { _ =>
          openRounds.map(_.payload.round.number).sorted.toSeq shouldBe Seq(1, 2, 3)
        }
      }
    }
  }
}
