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
        ConfigTransforms.updateAllSvAppConfigs_(
          _.focus(_.localSynchronizerNode)
            .modify(_.map(_.focus(_.sequencer.isBftSequencer).replace(true)))
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
          },
          svApps = conf.svApps.updatedWith(InstanceName.tryCreate("sv2")) {
            _.map { sv2Config =>
              sv2Config.copy(
                localSynchronizerNode = sv2Config.localSynchronizerNode.map(config =>
                  config.copy(sequencer =
                    config.sequencer.copy(
                      bftPeerEndpoints =
                        Seq(Endpoint("localhost", RequireTypes.Port.tryCreate(31030)))
                    )
                  )
                )
              )
            }
          },
        )
      )
      .withManualStart
  }

  "Splice apps" should {
    "start with uninitialized Canton nodes" in { implicit env =>
      sv1Backend.startSync()
      sv1Backend
        .sequencerClient(decentralizedSynchronizerId)
        .bft
        .add_peer_endpoint(
          Endpoint(
            "localhost",
            RequireTypes.Port.tryCreate(
              31031
            ),
          )
        )
      sv2Backend.startSync()
    }
  }

}
