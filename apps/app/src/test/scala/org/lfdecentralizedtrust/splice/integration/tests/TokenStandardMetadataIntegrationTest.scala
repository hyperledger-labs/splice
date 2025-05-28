package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.tokenstandard.metadata.v1

class TokenStandardMetadataIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "Scan implements token metadata API" in { implicit env =>
    val dso = sv1ScanBackend.getDsoPartyId().toProtoPrimitive

    val amuletInstrument = v1.definitions.Instrument(
      id = "Amulet",
      name = "Amulet",
      symbol = "Amulet",
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

    clue("listInstruments") {
      sv1ScanBackend.listInstruments().loneElement shouldBe amuletInstrument
      sv1ScanBackend.listInstruments() shouldBe aliceValidatorBackend.scanProxy.listInstruments()
    }

    clue("lookupInstrument") {

      sv1ScanBackend.lookupInstrument(amuletInstrument.id) shouldBe Some(
        amuletInstrument
      )
      sv1ScanBackend.lookupInstrument(amuletInstrument.id) shouldBe aliceValidatorBackend.scanProxy
        .lookupInstrument(amuletInstrument.id)

      sv1ScanBackend.lookupInstrument("non-existent") shouldBe None
      sv1ScanBackend.lookupInstrument("non-existent") shouldBe aliceValidatorBackend.scanProxy
        .lookupInstrument("non-existent")
    }
  }
}
