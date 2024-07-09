package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.scan.config.ScanSynchronizerConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SoftDomainMigrationTopologySetupIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "simple-topology-soft-domain-upgrade.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .addConfigTransformsToFront(
        (_, conf) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs_ { conf =>
            val synchronizerConfig = conf.localSynchronizerNode.value
            conf.copy(
              synchronizerNodes = Map(
                "global-domain" -> synchronizerConfig,
                "global-domain-new" -> CNNodeConfigTransforms
                  .setSvSynchronizerConfigPortsPrefix(28, synchronizerConfig),
              ),
              supportsSoftDomainMigrationPoc = true,
            )
          }(conf),
        (_, conf) =>
          CNNodeConfigTransforms.updateAllScanAppConfigs { (name, scanConf) =>
            val svConf = conf.svApps(InstanceName.tryCreate(name.stripSuffix("Scan")))
            scanConf.copy(
              synchronizers = svConf.synchronizerNodes.view
                .mapValues(c =>
                  ScanSynchronizerConfig(
                    c.sequencer.adminApi,
                    c.mediator.adminApi,
                  )
                )
                .toMap,
              supportsSoftDomainMigrationPoc = true,
            )
          }(conf),
      )

  "SVs can bootstrap new domain" in { implicit env =>
    env.scans.local should have size 4
    val prefix = "global-domain-new"
    eventually() {
      val dsoInfo = sv1Backend.getDsoInfo()
      dsoInfo.svNodeStates.values.flatMap(
        _.payload.state.synchronizerNodes.asScala.values.flatMap(_.scan.toScala)
      ) should have size 4
    }
    env.svs.local.foreach { sv =>
      sv.signSynchronizerBootstrappingState(prefix)
    }
    env.svs.local.foreach { sv =>
      sv.initializeSynchronizer(prefix)
    }
  // TODO(#13372) Reenable this once Canton fixes this
  // env.svs.local.foreach { sv =>
  //   sv.participantClient.domains.register_with_config(
  //     DomainConnectionConfig(
  //       DomainAlias.tryCreate(prefix),
  //       SequencerConnections.single(LocalSynchronizerNode.toSequencerConnection(
  //         sv.config.synchronizerNodes(prefix).sequencer.internalApi,
  //       ))
  //     ),
  //     handshakeOnly = false,
  //   )
  // }
  }
}
