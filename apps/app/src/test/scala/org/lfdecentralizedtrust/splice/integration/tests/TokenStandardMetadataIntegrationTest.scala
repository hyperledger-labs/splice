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
      instrumentId = v1.definitions.InstrumentId(
        providerId = dso,
        id = "Amulet",
      ),
      name = "Amulet",
      symbol = "Amulet",
    )

    sv1ScanBackend.getRegistryInfo() shouldBe v1.definitions.GetRegistryInfoResponse(
      providerId = dso,
      supportedStandards = Vector("metadata=1.0", "holding=1.0", "transfer-instruction=1.0"),
    )

    sv1ScanBackend.listInstruments().loneElement shouldBe amuletInstrument

    sv1ScanBackend.lookupInstrument(amuletInstrument.instrumentId.id) shouldBe Some(
      amuletInstrument
    )
    sv1ScanBackend.lookupInstrument("non-existent") shouldBe None
  }
}
