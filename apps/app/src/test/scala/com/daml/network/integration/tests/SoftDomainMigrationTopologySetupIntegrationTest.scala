package com.daml.network.integration.tests

import com.daml.network.automation.AssignTrigger
import com.daml.network.codegen.java.splice.amuletconfig.AmuletConfig
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import com.daml.network.codegen.java.splice.decentralizedsynchronizer.AmuletDecentralizedSynchronizerConfig
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import com.daml.network.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import com.daml.network.config.{ConfigTransforms, Thresholds}
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.scan.config.ScanSynchronizerConfig
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.automation.singlesv.AmuletConfigReassignmentTrigger
import com.daml.network.util.{Codec, ConfigScheduleUtil, WalletTestUtil}
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{SequencerConnections, SubmissionRequestAmplification}
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}

import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SoftDomainMigrationTopologySetupIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil {

  // Does not currently handle multiple synchronizers.
  override def runUpdateHistorySanityCheck = false

  override def environmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "simple-topology-soft-domain-upgrade.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      // TODO(#13715) Reenable this
      .withTrafficTopupsDisabled
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
        (_, conf) =>
          ConfigTransforms.updateAllValidatorAppConfigs_(c =>
            c.copy(
              supportsSoftDomainMigrationPoc = true
            )
          )(conf),
        (_, conf) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            _.withResumedTrigger[AmuletConfigReassignmentTrigger]
              .withResumedTrigger[AssignTrigger]
          )(conf),
      )

  "SVs can bootstrap new domain" in { implicit env =>
    val (_, bob) = onboardAliceAndBob()
    env.scans.local should have size 4
    val prefix = "global-domain-new"
    eventually() {
      val dsoInfo = sv1Backend.getDsoInfo()
      dsoInfo.svNodeStates.values.flatMap(
        _.payload.state.synchronizerNodes.asScala.values.flatMap(_.scan.toScala)
      ) should have size 4
    }
    // Enough time that the voting flow can go through
    val scheduledTime = env.environment.clock.now.plus(java.time.Duration.ofSeconds(30)).toInstant
    val amuletConfig =
      sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue
    val existingDomainId =
      Codec.tryDecode(Codec.DomainId)(amuletConfig.decentralizedSynchronizer.activeSynchronizer)
    val newDomainId = DomainId(UniqueIdentifier.tryCreate(prefix, existingDomainId.uid.namespace))
    val newAmuletConfig =
      new AmuletConfig(
        amuletConfig.transferConfig,
        amuletConfig.issuanceCurve,
        new AmuletDecentralizedSynchronizerConfig(
          new com.daml.network.codegen.java.da.set.types.Set(
            (amuletConfig.decentralizedSynchronizer.requiredSynchronizers.map.asScala.toMap + (newDomainId.toProtoPrimitive -> com.daml.ledger.javaapi.data.Unit
              .getInstance())).asJava
          ),
          newDomainId.toProtoPrimitive,
          amuletConfig.decentralizedSynchronizer.fees,
        ),
        amuletConfig.tickDuration,
        amuletConfig.packageConfig,
      )

    // tap before the migration
    aliceWalletClient.tap(100)

    val (_, voteRequest) = actAndCheck(
      "Creating vote request",
      eventuallySucceeds() {
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          new ARC_AmuletRules(
            new CRARC_AddFutureAmuletConfigSchedule(
              new AmuletRules_AddFutureAmuletConfigSchedule(
                new com.daml.network.codegen.java.da.types.Tuple2(
                  scheduledTime,
                  newAmuletConfig,
                )
              )
            )
          ),
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        )
      },
    )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

    clue(s"sv2-4 accept") {
      Seq(sv2Backend, sv3Backend, sv4Backend).map(sv =>
        eventuallySucceeds() {
          sv.castVote(
            voteRequest.contractId,
            true,
            "url",
            "description",
          )
        }
      )
    }

    eventually() {
      sv1ScanBackend.getAmuletRules().payload.configSchedule.futureValues should not be empty
    }

    clue("Bootstrap new domain") {
      env.svs.local.foreach { sv =>
        sv.signSynchronizerBootstrappingState(prefix)
      }
      env.svs.local.foreach { sv =>
        sv.initializeSynchronizer(prefix)
      }
    }
    clue("All participants connect to new domain") {
      val domainAlias = DomainAlias.tryCreate(prefix)
      val connections = clue("SV participants connect to new domain") {
        env.svs.local.map { sv =>
          val participant = sv.participantClient
          val connection =
            LocalSynchronizerNode.toSequencerConnection(
              sv.config.synchronizerNodes(prefix).sequencer.internalApi,
              SequencerAlias.tryCreate(sv.config.onboarding.value.name),
            )
          participant.domains.register_with_config(
            DomainConnectionConfig(
              domainAlias,
              SequencerConnections.single(connection),
            ),
            handshakeOnly = false,
          )
          val domainId = participant.domains.id_of(domainAlias)
          participant.health.ping(
            participant.id,
            domainId = Some(domainId),
          )
          connection
        }
      }
      clue("Non-SV validator participants connect to new domain") {
        val sequencerConnections = SequencerConnections.tryMany(
          connections,
          Thresholds.sequencerConnectionsSizeThreshold(connections.size),
          SubmissionRequestAmplification(
            Thresholds.sequencerSubmissionRequestAmplification(connections.size),
            NonNegativeFiniteDuration.ofSeconds(10),
          ),
        )
        env.validators.local.map { validator =>
          if (!validator.name.startsWith("sv")) {
            val participant = validator.participantClient
            participant.domains.register_with_config(
              DomainConnectionConfig(
                domainAlias,
                sequencerConnections,
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
    }
    clue("Sign DSO PartToParticipant mapping") {
      env.svs.local.foreach { sv =>
        sv.signDsoPartyToParticipant(prefix)
      }
    }

    // Ensure that the scheduled time has passed.
    // This is mainly to avoid putting a stupidly large time in the eventually below.
    env.environment.clock
      .scheduleAt(
        _ => (),
        CantonTimestamp.assertFromInstant(scheduledTime.plus(500, ChronoUnit.MILLIS)),
      )
      .unwrap
      .futureValue

    eventually() {
      sv1ScanBackend.getAmuletRules().state shouldBe ContractState.Assigned(newDomainId)
      val (openRounds, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      forAll(openRounds) { round =>
        round.state shouldBe ContractState.Assigned(newDomainId)
      }
      forAll(issuingRounds) { round =>
        round.state shouldBe ContractState.Assigned(newDomainId)
      }
    }

    p2pTransfer(
      aliceWalletClient,
      bobWalletClient,
      bob,
      42.0,
    )

    val aliceAmulets = aliceWalletClient.list().amulets
    aliceAmulets should not be empty
    forAll(aliceAmulets) {
      _.contract.state shouldBe ContractState.Assigned(newDomainId)
    }

    // Eventually to allow merging to also reassign
    // any other contracts.
    eventually() {
      val bobAmulets = bobWalletClient.list().amulets
      bobAmulets should not be empty
      forAll(bobAmulets) {
        _.contract.state shouldBe ContractState.Assigned(newDomainId)
      }
    }
  }
}
