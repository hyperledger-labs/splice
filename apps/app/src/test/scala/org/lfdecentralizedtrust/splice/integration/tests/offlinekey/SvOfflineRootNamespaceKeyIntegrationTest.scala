package org.lfdecentralizedtrust.splice.integration.tests.offlinekey

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ScanAppBackendReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}

class SvOfflineRootNamespaceKeyIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil
    with OfflineRootNamespaceKeyUtil {

  override def usesDbs: Seq[String] =
    super[StandaloneCanton].usesDbs ++ super[OfflineRootNamespaceKeyUtil].usesDbs

  override def dbsSuffix: String = "offline_root_key"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  private val cantonNameSuffix: String = getClass.getSimpleName

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(cantonNameSuffix)
      // we start the participants during the test so we cannot pre-allocate
      .withPreSetup(_ => ())
      .addConfigTransforms((_, config) =>
        // use a fresh participant to ensure a fresh deployment so we can validate the topology state
        ConfigTransforms.bumpCantonPortsBy(22_000)(config)
      )
      // Add a suffix to the canton identifiers to avoid metric conflicts with the shared canton nodes
      .withCantonNodeNameSuffix(cantonNameSuffix)
      .withTrafficTopupsDisabled
      .withManualStart

  "start svs with offline root namespace keys" in { implicit env =>
    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
      logSuffix = "offline-root-keys",
      sv4 = false,
    )() {
      initSvNodesWithOfflineRootNamespaceKey(sv1Backend, sv1ScanBackend)
      initSvNodesWithOfflineRootNamespaceKey(sv2Backend, sv2ScanBackend)
    }
  }

  private def initSvNodesWithOfflineRootNamespaceKey(
      backend: SvAppBackendReference,
      scan: ScanAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val participantClient = backend.participantClientWithAdminToken
    val sequencerClient = backend.sequencerClient
    val mediatorClient = backend.mediatorClient
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      participantClient,
    )
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      sequencerClient,
    )
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      mediatorClient,
    )
    scan.start()
    backend.startSync()
    scan.waitForInitialization()
    instanceHasNoRootNamespaceKey(participantClient)
    instanceHasNoRootNamespaceKey(sequencerClient)
    instanceHasNoRootNamespaceKey(mediatorClient)
  }
}
