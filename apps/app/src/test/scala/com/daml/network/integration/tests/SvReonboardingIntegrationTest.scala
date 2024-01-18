package com.daml.network.integration.tests

import com.daml.network.sv.automation.singlesv.membership.offboarding.SvOffboardingPartyToParticipantProposalTrigger
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.config.RequireTypes.{Port, PositiveInt}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.topology.{Identifier, ParticipantId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId.{AuthorizedStore, DomainStore}
import com.digitalasset.canton.topology.transaction.ParticipantPermissionX
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.*
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.*
import com.daml.network.config.{CNNodeConfigTransforms, CNParticipantClientConfig}
import com.daml.network.environment.{CNNodeEnvironmentImpl, DarResources}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.util.ProcessTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import scala.jdk.CollectionConverters.*
import org.scalatest.time.{Minute, Span}
import com.google.protobuf.ByteString
import scala.util.Using
import java.nio.file.Files

// TODO(#9076) Create fresh database instances within the test to support running it multiple times.
class SvReonboardingIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistend stores
  registerPlugin(new UseInMemoryStores(loggerFactory))

  private def tweakSv4ParticipantConfig(conf: CNParticipantClientConfig) =
    // Remap to the participant started through withCanton
    conf.copy(
      adminApi = conf.adminApi.copy(
        port = Port.tryCreate(28402)
      ),
      ledgerApi = conf.ledgerApi.copy(
        clientConfig = conf.ledgerApi.clientConfig.copy(
          port = Port.tryCreate(28401)
        )
      ),
    )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      // SV4’s participant is not yet running here so we don't allocate it's user.
      // Instead it is allocated by canton through `ledger-api.user-management-service.admin-user-id`.
      .withAllocatedUsers(extraIgnoredSvPrefixes = Seq("sv4"))
      .addConfigTransforms(
        (_, config) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs((name, conf) => {
            if (name == "sv4") {
              conf.copy(
                participantClient = tweakSv4ParticipantConfig(conf.participantClient),
                // TODO(#9284) Also test reonboarding sequencers/mediators with fresh dbs.
                localDomainNode = None,
              )
            } else {
              conf
            }
          })(config),
        (_, config) =>
          CNNodeConfigTransforms.updateAllValidatorConfigs((name, conf) => {
            if (name == "sv4Validator") {
              conf.copy(participantClient = tweakSv4ParticipantConfig(conf.participantClient))
            } else {
              conf
            }
          })(config),
      )
      .withManualStart

  "restore SV from namespace only" in { implicit env =>
    val (dump, sv4Party) = withCanton(
      Seq(testResourcesPath / "sv-reonboard-participant.conf"),
      Seq("canton.participants-x.sv4Reonboard.init.auto-init=true"),
      "sv reonboarding",
      "ADMIN_USER" -> sv4Backend.config.ledgerApiUser,
    ) {

      startAllSync(
        sv1ScanBackend,
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

      sv1Backend.getSvcInfo().svcRules.payload.members.keySet.asScala shouldBe Set(
        sv1Party,
        sv2Party,
        sv3Party,
        sv4Party,
      ).map(_.toProtoPrimitive)

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
        }
      }

      // TODO(#9142) Keep offboarding triggers running once they don't offboard SV4’s new participant
      // immediately because it uses the same namespace.
      Seq(sv1Backend, sv2Backend, sv3Backend).foreach { sv =>
        sv.svcAutomation.trigger[SvOffboardingPartyToParticipantProposalTrigger].pause().futureValue
      }

      val dump = sv4ValidatorBackend.dumpParticipantIdentities()

      // Stop SV4
      clue("Stop SV4") {
        sv4Backend.stop()
        sv4ValidatorBackend.stop()
      }
      (dump, sv4Party)
    }

    withCanton(
      Seq(testResourcesPath / "sv-reonboard-participant.conf"),
      Seq(
        "canton.participants-x.sv4Reonboard.init.auto-init=false",
        "canton.participants-x.sv4Reonboard.storage.config.properties.databaseName=participant_sv4_reonboard_new",
      ),
      "sv reonboarding new",
      "ADMIN_USER" -> sv4Backend.config.ledgerApiUser,
    ) {
      // We need to clear the cache, otherwise the participant id gets cached in memory and
      // some commands still use the old participant id.
      sv4Backend.participantClientWithAdminToken.clear_cache()
      eventually() {
        sv4Backend.participantClientWithAdminToken.health.status shouldBe NodeStatus.NotInitialized(
          true
        )
      }

      val (newParticipantId, _) = actAndCheck(
        "Initializing new SV4 participant", {
          eventuallySucceeds() {
            sv4Backend.participantClientWithAdminToken.topology.transactions
              .load(dump.bootstrapTxs, AuthorizedStore.filterName)
          }

          dump.keys.foreach { key =>
            sv4Backend.participantClientWithAdminToken.keys.secret
              .upload(ByteString.copyFrom(key.keyPair), key.name)
          }
          val newParticipantId = ParticipantId(
            UniqueIdentifier(Identifier.tryCreate("sv4ReonboardNew"), dump.id.uid.namespace)
          )
          // We need to create OwnerToKeyMappingX transactions for init_id to work properly.
          // They are not included in the import from dump since that obviously only has the txs for the old
          // participant id.
          sv4Backend.participantClientWithAdminToken.keys.secret.list().map { key =>
            sv4Backend.participantClientWithAdminToken.topology.owner_to_key_mappings.add_key(
              key.id,
              key.purpose,
              keyOwner = newParticipantId,
              signedBy = Some(newParticipantId.uid.namespace.fingerprint),
            )
          }
          sv4Backend.participantClientWithAdminToken.topology.init_id(newParticipantId.uid)
          newParticipantId
        },
      )(
        "sv4 participant is initialized",
        _ =>
          sv4Backend.participantClientWithAdminToken.health.status shouldBe a[NodeStatus.Success[_]],
      )
      sv4Backend.participantClientWithAdminToken.id shouldBe newParticipantId
      clue("Connect new participant global domain") {
        sv4Backend.participantClientWithAdminToken.domains.connect(
          DomainConnectionConfig(
            sv4Backend.config.domains.global.alias,
            SequencerConnections.single(
              GrpcSequencerConnection.tryCreate(sv4Backend.config.domains.global.url)
            ),
          )
        )
      }
      val globalDomainId =
        sv4Backend.participantClientWithAdminToken.domains.list_connected().loneElement.domainId
      actAndCheck(
        "Submit topology transaction to migrate SV4 party", {
          val topology =
            sv4Backend.participantClientWithAdminToken.topology.party_to_participant_mappings
              .list(filterParty = sv4Party.toProtoPrimitive)
              .loneElement
          val newSerial = Some(topology.context.serial.increment)
          sv4Backend.participantClientWithAdminToken.topology.party_to_participant_mappings.propose(
            sv4Party,
            newParticipants = Seq((newParticipantId, ParticipantPermissionX.Submission)),
            store = DomainStore(globalDomainId).filterName,
            serial = newSerial,
          )
          newSerial
        },
      )(
        "topology transaction is committed",
        newSerial => {
          val newTopology =
            sv4Backend.participantClientWithAdminToken.topology.party_to_participant_mappings
              .list(filterParty = sv4Party.toProtoPrimitive)
          Some(newTopology.loneElement.context.serial) shouldBe newSerial
        },
      )
      clue("Import ACS") {
        sv4Backend.participantClientWithAdminToken.domains.disconnect_all()
        (DarResources.svcGovernance.all ++ DarResources.wallet.all ++ DarResources.svLocal.all)
          .map { dar =>
            val darData = Using.resource(getClass.getClassLoader.getResourceAsStream(dar.path))(
              ByteString.readFrom(_)
            )
            sv4Backend.participantClientWithAdminToken.dars
              .upload(dar.path, darDataO = Some(darData))
          }
        val acsExport = sv1ScanBackend.getAcsSnapshot(sv4Party)
        val acsExportFile = Files.createTempFile("acs-export", ".gz")
        try {
          Using.resource(Files.newOutputStream(acsExportFile))(acsExport.writeTo(_))
          sv4Backend.participantClientWithAdminToken.repair
            .import_acs(inputFile = acsExportFile.toString)
        } finally {
          Files.delete(acsExportFile)
        }
        sv4Backend.participantClientWithAdminToken.domains.reconnect_all()
      }
      sv4Backend.startSync()
      val mapping = sv1Backend.appState.participantAdminConnection
        .getPartyToParticipant(globalDomainId, sv1Backend.getSvcInfo().svcParty)
        .futureValue
        .mapping
      mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
        sv1Backend.participantClient.id,
        sv2Backend.participantClient.id,
        sv3Backend.participantClient.id,
        sv4Backend.participantClient.id,
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
        sv4Backend.createVoteRequest(
          sv4Party.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        ),
      )(
        "vote request is observed by sv1-3",
        _ => {
          val voteRequest = sv4Backend.listVoteRequests().loneElement
          voteRequest.payload.action shouldBe action
          Seq(sv1Backend, sv2Backend, sv3Backend).foreach { sv =>
            forExactly(1, sv.listVoteRequests()) { _.contractId shouldBe voteRequest.contractId }
          }
        },
      )
    }
  }
}
