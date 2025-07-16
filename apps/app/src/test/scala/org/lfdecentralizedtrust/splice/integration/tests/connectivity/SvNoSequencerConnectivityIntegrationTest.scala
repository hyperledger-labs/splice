package org.lfdecentralizedtrust.splice.integration.tests.connectivity

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy.UseToxiproxy
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

class SvNoSequencerConnectivityIntegrationTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransform({ case (_, c) =>
        c.copy(
          svApps = c.svApps + (InstanceName.tryCreate("sv1Local") ->
            c.svApps(InstanceName.tryCreate("sv1"))
              .copy(
                skipSynchronizerInitialization = true
              )) + (InstanceName.tryCreate("sv2Local") ->
            c.svApps(InstanceName.tryCreate("sv2"))
              .copy(
                skipSynchronizerInitialization = true
              ))
        )
      })
      .withManualStart

  private val toxiproxy = UseToxiproxy(
    createSequencerProxies = true,
    instanceFilter = (name: String) => name.contains("Local"),
  )
  registerPlugin(toxiproxy)

  "SV app can restart without working sequencer" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv2Backend,
      sv1ScanBackend,
      sv2ScanBackend,
      sv1ValidatorBackend,
      sv2ValidatorBackend,
    )

    forAll(Seq((sv1Backend, sv1LocalBackend), (sv2Backend, sv2LocalBackend))) {
      case (sv, svWithoutSequencer) =>
        clue(s"SV ${sv.name} can start without sequencer") {
          sv.stop()
          toxiproxy.disableConnectionViaProxy(
            UseToxiproxy.sequencerAdminApi(svWithoutSequencer.name)
          )
          toxiproxy.disableConnectionViaProxy(
            UseToxiproxy.sequencerPublicApi(svWithoutSequencer.name)
          )
          // Check that sequencer connection really doesn't work anymore.
          svWithoutSequencer.sequencerClient.health.status.toString should include("UNAVAILABLE")
          svWithoutSequencer.startSync()
        }
    }
  }
}
