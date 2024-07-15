package com.daml.network.integration.tests

import com.daml.network.config.ConfigTransforms
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.scan.config.ScanSynchronizerConfig
import com.daml.network.sv.LocalSynchronizerNode
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SoftDomainMigrationTopologySetupIntegrationTest extends IntegrationTest {

  override def environmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "simple-topology-soft-domain-upgrade.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .addConfigTransformsToFront(
        (_, conf) =>
          ConfigTransforms.updateAllSvAppConfigs_ { conf =>
            val synchronizerConfig = conf.localSynchronizerNode.value
            conf.copy(
              synchronizerNodes = Map(
                "global-domain" -> synchronizerConfig,
                "global-domain-new" -> ConfigTransforms
                  .setSvSynchronizerConfigPortsPrefix(28, synchronizerConfig),
              ),
              supportsSoftDomainMigrationPoc = true,
            )
          }(conf),
        (_, conf) =>
          ConfigTransforms.updateAllScanAppConfigs { (name, scanConf) =>
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
    val domainAlias = DomainAlias.tryCreate(prefix)
    env.svs.local.foreach { sv =>
      val participant = sv.participantClient
      participant.domains.register_with_config(
        DomainConnectionConfig(
          domainAlias,
          SequencerConnections.single(
            LocalSynchronizerNode.toSequencerConnection(
              sv.config.synchronizerNodes(prefix).sequencer.internalApi
            )
          ),
        ),
        handshakeOnly = false,
      )
      val domainId = participant.domains.id_of(domainAlias)
      participant.health.ping(
        participant.id,
        domainId = Some(domainId),
      )
    }
  }
}
