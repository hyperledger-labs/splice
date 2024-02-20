package com.daml.network.integration.tests

import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.*
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.*
import com.daml.network.config.{
  CNNodeConfigTransforms,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
}
import CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.sv.automation.singlesv.offboarding.SvOffboardingMediatorTrigger
import com.daml.network.sv.automation.singlesv.membership.offboarding.SvOffboardingSequencerTrigger
import com.daml.network.sv.config.MigrateSvPartyConfig
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.util.{ProcessTestUtil, StandaloneCanton}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*
import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.time.{Minute, Span}

import java.nio.file.Files

class SvReonboardingIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with StandaloneCanton {

  override def dbsSuffix = "reonboard"
  override def usesDbs =
    Seq(
      "participant_sv4_reonboard_new",
      "sequencer_sv4_reonboard_new",
      "mediator_sv4_reonboard_new",
    ) ++ super.usesDbs

  // Runs against a temporary Canton instance.
  override lazy val resetDecentralizedNamespace = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistend stores
  registerPlugin(new UseInMemoryStores(loggerFactory))

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  private def sv4ReonboardBackend(implicit env: CNNodeTestConsoleEnvironment) = svb("sv4Reonboard")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // Disable user allocation
      .withPreSetup(_ => ())
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransforms(
        (_, config) =>
          config.copy(
            svApps = config.svApps +
              (InstanceName.tryCreate("sv4Reonboard") ->
                config
                  .svApps(InstanceName.tryCreate("sv4"))
                  .copy(
                    participantBootstrappingDump =
                      Some(ParticipantBootstrapDumpConfig.File(dumpPath, Some("sv4ReonboardNew"))),
                    migrateSvParty = Some(
                      MigrateSvPartyConfig(
                        ScanAppClientConfig(
                          adminApi = NetworkAppClientConfig(
                            Uri(s"http://localhost:5012")
                          )
                        )
                      )
                    ),
                  ))
          ),
        (_, config) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withResumedTrigger[SvOffboardingMediatorTrigger]
              .withResumedTrigger[SvOffboardingSequencerTrigger]
          )(config),
      )
      .withManualStart

  "restore SV from namespace only" in { implicit env =>
    // Mediators/sequencers that have been offboarderded stay in a broken state which is fine in prod
    // (you can onboard a fresh mediator/sequencer)
    // but annoying in tests so we use a dedicated Canton instance for this test.
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv3Backend),
        None,
      ),
      logSuffix = "sv123-reonboarding",
      sv4 = false,
    )() {

      val (
        dump,
        sv4Party,
        (sv1MediatorId, sv2MediatorId, sv3MediatorId, _),
        (sv1SequencerId, sv2SequencerId, sv3SequencerId, _),
      ) = withCantonSvNodes(
        (None, None, None, Some(sv4Backend)),
        logSuffix = "sv4-reonboarding",
        svs123 = false,
      )() {
        startAllSync(
          sv1ScanBackend,
          sv2ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
          sv4ValidatorBackend,
        )

        val sv1Party = sv1Backend.getSvcInfo().svParty
        val sv2Party = sv2Backend.getSvcInfo().svParty
        val sv3Party = sv3Backend.getSvcInfo().svParty
        val sv4Party = sv4Backend.getSvcInfo().svParty

        val (sv1MediatorId, sv2MediatorId, sv3MediatorId, sv4MediatorId) =
          inside(
            Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
              _.appState.localDomainNode.value.mediatorAdminConnection.getMediatorId.futureValue
            )
          ) { case Seq(sv1, sv2, sv3, sv4) =>
            (sv1, sv2, sv3, sv4)
          }
        val (sv1SequencerId, sv2SequencerId, sv3SequencerId, sv4SequencerId) =
          inside(
            Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
              _.appState.localDomainNode.value.sequencerAdminConnection.getSequencerId.futureValue
            )
          ) { case Seq(sv1, sv2, sv3, sv4) =>
            (sv1, sv2, sv3, sv4)
          }

        sv1Backend.getSvcInfo().svcRules.payload.members.keySet.asScala shouldBe Set(
          sv1Party,
          sv2Party,
          sv3Party,
          sv4Party,
        ).map(_.toProtoPrimitive)

        sv1Backend.appState.participantAdminConnection
          .getMediatorDomainState(globalDomainId)
          .futureValue
          .mapping
          .active
          .forgetNE should contain theSameElementsAs Seq(
          sv1MediatorId,
          sv2MediatorId,
          sv3MediatorId,
          sv4MediatorId,
        )
        sv1Backend.appState.participantAdminConnection
          .getSequencerDomainState(globalDomainId)
          .futureValue
          .mapping
          .active
          .forgetNE should contain theSameElementsAs Seq(
          sv1SequencerId,
          sv2SequencerId,
          sv3SequencerId,
          sv4SequencerId,
        )

        clue("Offboard SV4") {
          val (_, voteRequestCid) = actAndCheck(
            "SV1 create a vote request to remove sv4", {
              val action: ActionRequiringConfirmation =
                new ARC_SvcRules(
                  new SRARC_RemoveMember(new SvcRules_RemoveMember(sv4Party.toProtoPrimitive))
                )
              sv1Backend.createVoteRequest(
                sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                action,
                "url",
                "description",
                sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
              )
            },
          )(
            "The vote request has been created",
            _ => sv1Backend.listVoteRequests().loneElement.contractId,
          )

          Seq(sv2Backend, sv3Backend).foreach { sv =>
            eventually() {
              sv.listVoteRequests() should have size 1
            }
            sv.castVote(voteRequestCid, true, "url", "description")
          }

          eventually() {
            sv1Backend.getSvcInfo().svcRules.payload.members.keySet.asScala shouldBe Set(
              sv1Party,
              sv2Party,
              sv3Party,
            ).map(_.toProtoPrimitive)
            val mapping = sv1Backend.appState.participantAdminConnection
              .getPartyToParticipant(globalDomainId, sv1Backend.getSvcInfo().svcParty)
              .futureValue
              .mapping
            mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
              sv1Backend.participantClient.id,
              sv2Backend.participantClient.id,
              sv3Backend.participantClient.id,
            )
            sv1Backend.appState.participantAdminConnection
              .getMediatorDomainState(globalDomainId)
              .futureValue
              .mapping
              .active
              .forgetNE should contain theSameElementsAs Seq(
              sv1MediatorId,
              sv2MediatorId,
              sv3MediatorId,
            )
            sv1Backend.appState.participantAdminConnection
              .getSequencerDomainState(globalDomainId)
              .futureValue
              .mapping
              .active
              .forgetNE should contain theSameElementsAs Seq(
              sv1SequencerId,
              sv2SequencerId,
              sv3SequencerId,
            )
          }
        }

        val dump = sv4ValidatorBackend.dumpParticipantIdentities()

        // Stop SV4
        clue("Stop SV4") {
          sv4Backend.stop()
          sv4ValidatorBackend.stop()
        }
        (
          dump,
          sv4Party,
          (sv1MediatorId, sv2MediatorId, sv3MediatorId, sv4MediatorId),
          (sv1SequencerId, sv2SequencerId, sv3SequencerId, sv4SequencerId),
        )
      }

      withCantonSvNodes(
        (None, None, None, Some(sv4Backend)),
        logSuffix = "sv4-reonboarding-new",
        svs123 = false,
        overrideSvDbsSuffix = Some("reonboard_new"),
      )(
        "SV4_PARTICIPANT_AUTO_INIT" -> "false"
      ) {
        eventually() {
          sv4ReonboardBackend.participantClientWithAdminToken.health.status shouldBe NodeStatus
            .NotInitialized(
              true
            )
        }
        better.files
          .File(dumpPath)
          .overwrite(
            dump.toJson.noSpaces
          )
        sv4ReonboardBackend.startSync()
        val mapping = sv1Backend.appState.participantAdminConnection
          .getPartyToParticipant(globalDomainId, sv1Backend.getSvcInfo().svcParty)
          .futureValue
          .mapping
        mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
          sv1Backend.participantClient.id,
          sv2Backend.participantClient.id,
          sv3Backend.participantClient.id,
          sv4ReonboardBackend.participantClient.id,
        )
        val sv4MediatorIdNew =
          sv4ReonboardBackend.appState.localDomainNode.value.mediatorAdminConnection.getMediatorId.futureValue
        val sv4SequencerIdNew =
          sv4ReonboardBackend.appState.localDomainNode.value.sequencerAdminConnection.getSequencerId.futureValue
        sv1Backend.appState.participantAdminConnection
          .getMediatorDomainState(globalDomainId)
          .futureValue
          .mapping
          .active
          .forgetNE should contain theSameElementsAs Seq(
          sv1MediatorId,
          sv2MediatorId,
          sv3MediatorId,
          sv4MediatorIdNew,
        )
        sv1Backend.appState.participantAdminConnection
          .getSequencerDomainState(globalDomainId)
          .futureValue
          .mapping
          .active
          .forgetNE should contain theSameElementsAs Seq(
          sv1SequencerId,
          sv2SequencerId,
          sv3SequencerId,
          sv4SequencerIdNew,
        )
        mapping.threshold shouldBe PositiveInt.tryCreate(3)
        // Test that SV4 can submit transactions and they're observed by others.
        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(
            new SRARC_GrantFeaturedAppRight(
              new SvcRules_GrantFeaturedAppRight(sv4Party.toProtoPrimitive)
            )
          )
        actAndCheck(
          "SV4 can create vote requests",
          sv4ReonboardBackend.createVoteRequest(
            sv4Party.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          ),
        )(
          "vote request is observed by sv1-3",
          _ => {
            val voteRequest = sv4ReonboardBackend.listVoteRequests().loneElement
            voteRequest.payload.action shouldBe action
            Seq(sv1Backend, sv2Backend, sv3Backend).foreach { sv =>
              forExactly(1, sv.listVoteRequests()) { _.contractId shouldBe voteRequest.contractId }
            }
          },
        )
      }
    }
  }
}
