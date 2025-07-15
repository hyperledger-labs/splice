package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.scan.automation.ScanAggregationTrigger
import org.lfdecentralizedtrust.splice.util.{Codec, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.tokenstandard.metadata.v1

import java.time.ZoneOffset

class TokenStandardMetadataTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      // Start ScanAggregationTrigger in paused state, calling runOnce in tests
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[ScanAggregationTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(config =>
          config.copy(
            spliceInstanceNames = config.spliceInstanceNames.copy(
              amuletName = "MyAmulet",
              amuletNameAcronym = "MyA",
            )
          )
        )(config)
      )

  "Scan implements token metadata API" in { implicit env =>
    val dso = sv1ScanBackend.getDsoPartyId().toProtoPrimitive

    // tap some coin so the total supply is not completely uninteresting
    onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    aliceWalletClient.tap(100)

    val amuletInstrument = v1.definitions.Instrument(
      id = "Amulet",
      name = "MyAmulet",
      symbol = "MyA",
      decimals = 10,
      supportedApis = Map(
        "splice-api-token-metadata-v1" -> 1,
        "splice-api-token-holding-v1" -> 1,
        "splice-api-token-transfer-instruction-v1" -> 1,
        "splice-api-token-allocation-v1" -> 1,
        "splice-api-token-allocation-instruction-v1" -> 1,
      ),
    )

    clue("getRegistryInfo") {
      sv1ScanBackend.getRegistryInfo() shouldBe v1.definitions.GetRegistryInfoResponse(
        adminId = dso,
        supportedApis = Map("splice-api-token-metadata-v1" -> 1),
      )
      sv1ScanBackend.getRegistryInfo() shouldBe aliceValidatorBackend.scanProxy.getRegistryInfo()
    }

    clue("check instruments when no round totals have been aggregated") {
      clue("listInstruments") {
        sv1ScanBackend.listInstruments().loneElement shouldBe amuletInstrument
        sv1ScanBackend.listInstruments() shouldBe aliceValidatorBackend.scanProxy.listInstruments()
      }

      clue("lookupInstrument") {
        sv1ScanBackend.lookupInstrument(amuletInstrument.id) shouldBe Some(
          amuletInstrument
        )
        sv1ScanBackend.lookupInstrument(
          amuletInstrument.id
        ) shouldBe aliceValidatorBackend.scanProxy
          .lookupInstrument(amuletInstrument.id)

        sv1ScanBackend.lookupInstrument("non-existent") shouldBe None
        sv1ScanBackend.lookupInstrument("non-existent") shouldBe aliceValidatorBackend.scanProxy
          .lookupInstrument("non-existent")
      }
    }

    clue("Once round totals are defined they are served") {
      actAndCheck(
        "Advance rounds to a point where round totals are defined and the tapped amulet",
        // We sadly need 7 rounds as we need to get to a point where round 0 is closed
        for (i <- 1 to 7) {
          advanceRoundsByOneTick
          sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
        },
      )(
        "rounds are defined and include tapped amulet",
        _ => {
          val (roundNumber, _) = sv1ScanBackend.getRoundOfLatestData()
          val totalBalance = sv1ScanBackend
            .getTotalAmuletBalance(roundNumber)
            .getOrElse(fail("total balance not yet defined"))
          totalBalance should be >= walletUsdToAmulet(99.0)
        },
      )
      clue("Compare direct scan reads to instrument metadata") {
        val (roundNumber, effectiveAt) = sv1ScanBackend.getRoundOfLatestData()
        val totalSupply = sv1ScanBackend.getTotalAmuletBalance(roundNumber)
        val instrument = sv1ScanBackend
          .lookupInstrument(amuletInstrument.id)
          .getOrElse(fail("instrument.totalSupply must be defined at this point"))
        (
          instrument.totalSupply.map(Codec.tryDecode(Codec.BigDecimal)),
          instrument.totalSupplyAsOf,
        ) shouldBe (totalSupply, Some(effectiveAt.atOffset(ZoneOffset.UTC)))
      }
    }
  }
}
