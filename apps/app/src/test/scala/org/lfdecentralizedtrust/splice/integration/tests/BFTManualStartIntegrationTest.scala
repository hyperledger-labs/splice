package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.networking.Endpoint
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}

class BFTManualStartIntegrationTest extends IntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
    EnvironmentDefinition
      .simpleTopology4Svs("BFT")
      .withTrafficTopupsEnabled
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs((sv, appConfig) =>
          appConfig
            .focus(_.localSynchronizerNode)
            .modify(
              _.map(
                _.focus(_.sequencer).modify(
                  _.copy(
                    isBftSequencer = true,
                    externalPeerApiUrlSuffix = Some(
                      Endpoint(
                        "localhost",
                        RequireTypes.Port
                          .tryCreate(5010 + Integer.parseInt(sv.stripPrefix("sv")) * 100),
                      )
                    ),
                  )
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
      sv2Backend.startSync()
      sv3Backend.startSync()
      eventually() {
        forAll(Seq(sv1Backend, sv2Backend, sv3Backend)) { sv =>
          sv.appState.localSynchronizerNode.value.sequencerAdminConnection
            .listCurrentPeerEndpoints()
            .futureValue
            .size shouldBe 2
        }
      }
    }
  }

}
