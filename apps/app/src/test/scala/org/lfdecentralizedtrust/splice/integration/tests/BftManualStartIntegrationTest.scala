package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.config.BftSequencerConfig

class BftManualStartIntegrationTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology4Svs("BFT")
      .withTrafficTopupsEnabled
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs((_, appConfig) =>
          appConfig
            .focus(_.localSynchronizerNode)
            .modify(
              _.map(
                _.focus(_.sequencer).modify(
                  _.copy(
                    isBftSequencer = true
                  )
                )
              )
            )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((scan, config) =>
          config.copy(
            bftSequencers = Seq(
              BftSequencerConfig(
                0,
                config.sequencerAdminClient,
                s"http://localhost:${5010 + Integer.parseInt(scan.stripPrefix("sv").stripSuffix("Scan")) * 100}",
              )
            )
          )
        )(config)
      )
      // By default, alice validator connects to the splitwell domain. This test doesn't start the splitwell node.
      .addConfigTransform((_, conf) =>
        conf.copy(
          validatorApps = conf.validatorApps.updatedWith(InstanceName.tryCreate("aliceValidator")) {
            _.map { aliceValidatorConfig =>
              val withoutExtraDomains = aliceValidatorConfig.domains.copy(extra = Seq.empty)
              aliceValidatorConfig.copy(
                domains = withoutExtraDomains
              )
            }
          }
        )
      )
      .withManualStart
  }

  "Splice apps" should {
    "start with the bft sequencers" in { implicit env =>
      sv1Backend.startSync()
      sv1ScanBackend.startSync()
      // TODO(#19670) if possible, don't require parallel start of sv app and scan
      startAllSync(sv2Backend, sv2ScanBackend)
      startAllSync(sv3Backend, sv3ScanBackend)
      forAll(Seq(sv1Backend, sv2Backend, sv3Backend)) { sv =>
        sv.appState.localSynchronizerNode.value.sequencerAdminConnection
          .listCurrentPeerEndpoints()
          .futureValue
          .size shouldBe 2
      }
    }
  }

}
