package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.*
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
  ParticipantClientConfig,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  bumpUrl,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.config.MigrateValidatorPartyConfig
import com.digitalasset.canton.admin.api.client.data.{NodeStatus, WaitingForId}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{DbConfig, FullClientConfig}
import com.digitalasset.canton.config.RequireTypes.{Port, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.typesafe.config.ConfigValueFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvBftSequencerPeerOffboardingTrigger
import org.scalatest.time.{Minute, Span}

import java.nio.file.Files
import java.time.Duration as JDUration
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvReonboardingIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil {

  override def dbsSuffix = "reonboard"
  override def usesDbs =
    Seq(
      "participant_sv4_reonboard_new",
      "sequencer_sv4_reonboard_new",
      "sequencer_sv4_reonboard_new_bft",
      "mediator_sv4_reonboard_new",
      "sv_app_sv4_reonboard_new",
      "validator_sv4_reonboard_new",
      "participant_reonboard_new",
      "validator_app_reonboard_new",
    ) ++ super.usesDbs

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  private def sv4ReonboardBackend(implicit env: SpliceTestConsoleEnvironment) = svb("sv4Reonboard")
  private def sv4WalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ) = wc("sv4Wallet")
  private def sv4ReonboardValidatorBackend(implicit env: SpliceTestConsoleEnvironment) = v(
    "sv4ReonboardValidator"
  )
  private def validatorLocalBackend(implicit env: SpliceTestConsoleEnvironment) = v(
    "validatorLocal"
  )
  private def validatorLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("validatorWalletLocal")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // Disable user allocation
      .withPreSetup(_ => ())
      .addConfigTransformsToFront(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransforms(
        (_, config) =>
          config.copy(
            svApps = config.svApps +
              (InstanceName.tryCreate("sv4Reonboard") -> {
                val sv4Config = config
                  .svApps(InstanceName.tryCreate("sv4"))
                sv4Config
                  .copy(
                    storage = sv4Config.storage match {
                      case c: DbConfig.Postgres =>
                        c.copy(
                          config = c.config
                            .withValue(
                              "properties.databaseName",
                              ConfigValueFactory.fromAnyRef("sv_app_sv4_reonboard_new"),
                            )
                        )
                      case _ => throw new IllegalArgumentException("Only Postgres is supported")
                    },
                    svPartyHint = Some("digital-asset-eng-4-reonboard"),
                    // we don't start a new sv4 scan so just re-use an existing one
                    scan = config
                      .svApps(InstanceName.tryCreate("sv3"))
                      .scan,
                  )
              }),
            validatorApps = config.validatorApps +
              (InstanceName.tryCreate("sv4ReonboardValidator") -> {
                // The same as sv4Validator, but using the new participant identity,
                // which is configured later with `.withCantonNodeNameSuffix()`
                config
                  .validatorApps(InstanceName.tryCreate("sv4Validator"))
                  .copy(
                    // we don't start a new sv4 scan so just re-use an existing one
                    scanClient =
                      config.validatorApps(InstanceName.tryCreate("sv3Validator")).scanClient
                  )
              }) +
              (InstanceName.tryCreate("validatorLocal") -> {
                val referenceValidatorConfig =
                  config.validatorApps(InstanceName.tryCreate("aliceValidator"))
                referenceValidatorConfig
                  .copy(
                    adminApi = referenceValidatorConfig.adminApi
                      .copy(internalPort = Some(Port.tryCreate(27503))),
                    participantClient = ParticipantClientConfig(
                      FullClientConfig(port = Port.tryCreate(27502)),
                      referenceValidatorConfig.participantClient.ledgerApi.copy(
                        clientConfig =
                          referenceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                            port = Port.tryCreate(27501)
                          )
                      ),
                    ),
                    storage = referenceValidatorConfig.storage match {
                      case c: DbConfig.Postgres =>
                        c.copy(
                          config = c.config
                            .withValue(
                              "properties.databaseName",
                              ConfigValueFactory.fromAnyRef("validator_app_reonboard_new"),
                            )
                        )
                      case _ => throw new IllegalArgumentException("Only Postgres is supported")
                    },
                    participantBootstrappingDump = Some(
                      ParticipantBootstrapDumpConfig
                        .File(dumpPath, Some("validatorLocalNew"))
                    ),
                    migrateValidatorParty = Some(
                      MigrateValidatorPartyConfig(
                        ScanAppClientConfig(
                          adminApi = NetworkAppClientConfig(
                            Uri(s"http://localhost:5012")
                          )
                        )
                      )
                    ),
                    validatorPartyHint = config.svApps(InstanceName.tryCreate("sv4")).svPartyHint,
                    domains = referenceValidatorConfig.domains.copy(extra = Seq.empty),
                  )
              }),
            walletAppClients = config.walletAppClients + (
              InstanceName.tryCreate("validatorWalletLocal") -> {
                val referenceValidatorWalletConfig =
                  config.walletAppClients(InstanceName.tryCreate("aliceValidatorWallet"))

                referenceValidatorWalletConfig
                  .copy(
                    adminApi = referenceValidatorWalletConfig.adminApi
                      .copy(url =
                        bumpUrl(22_000, referenceValidatorWalletConfig.adminApi.url.toString())
                      )
                  )
              }
            ),
          ),
        (_, config) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withResumedTrigger[SvOffboardingMediatorTrigger]
              .withResumedTrigger[SvOffboardingSequencerTrigger]
              .withResumedTrigger[SvBftSequencerPeerOffboardingTrigger]
          )(config),
      )
      .withTrafficTopupsDisabled
      .withCantonNodeNameSuffix("SvReonboarding")
      .withManualStart

  "reonboard SV with new party id and recover amulet via new regular validator" in { implicit env =>
    // Mediators/sequencers that have been offboarded stay in a broken state which is fine in prod
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
        sv4ParticipantDump,
        sv4Name,
        (sv1Party, sv2Party, sv3Party, sv4Party),
        (sv1MediatorId, sv2MediatorId, sv3MediatorId, _),
        (sv1SequencerId, sv2SequencerId, sv3SequencerId, _),
      ) = withCantonSvNodes(
        (None, None, None, Some(sv4Backend)),
        logSuffix = "sv4-reonboarding",
        svs123 = false,
      )() {
        startAllSync(
          (sv1Nodes ++ sv2Nodes ++ sv3Nodes ++ sv4Nodes)*
        )
        val sv1Party = sv1Backend.getDsoInfo().svParty
        val sv2Party = sv2Backend.getDsoInfo().svParty
        val sv3Party = sv3Backend.getDsoInfo().svParty
        val sv4Party = sv4Backend.getDsoInfo().svParty

        val sv4Name =
          sv1Backend
            .getDsoInfo()
            .dsoRules
            .payload
            .svs
            .asScala
            .get(sv4Party.toProtoPrimitive)
            .value
            .name

        val (sv1MediatorId, sv2MediatorId, sv3MediatorId, sv4MediatorId) =
          inside(
            Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
              _.appState.localSynchronizerNode.value.mediatorAdminConnection.getMediatorId.futureValue
            )
          ) { case Seq(sv1, sv2, sv3, sv4) =>
            (sv1, sv2, sv3, sv4)
          }
        val (sv1SequencerId, sv2SequencerId, sv3SequencerId, sv4SequencerId) =
          inside(
            Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
              _.appState.localSynchronizerNode.value.sequencerAdminConnection.getSequencerId.futureValue
            )
          ) { case Seq(sv1, sv2, sv3, sv4) =>
            (sv1, sv2, sv3, sv4)
          }

        sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet.asScala shouldBe Set(
          sv1Party,
          sv2Party,
          sv3Party,
          sv4Party,
        ).map(_.toProtoPrimitive)

        sv1Backend.appState.participantAdminConnection
          .getMediatorSynchronizerState(decentralizedSynchronizerId)
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
          .getSequencerSynchronizerState(decentralizedSynchronizerId)
          .futureValue
          .mapping
          .active
          .forgetNE should contain theSameElementsAs Seq(
          sv1SequencerId,
          sv2SequencerId,
          sv3SequencerId,
          sv4SequencerId,
        )

        sv4WalletClient.tap(100)
        val sv4ParticipantDump = sv4ValidatorBackend.dumpParticipantIdentities()

        clue("Offboard SV4") {
          val (_, voteRequestCid) = actAndCheck(
            "SV1 create a vote request to remove sv4", {
              val action: ActionRequiringConfirmation =
                new ARC_DsoRules(
                  new SRARC_OffboardSv(new DsoRules_OffboardSv(sv4Party.toProtoPrimitive))
                )
              sv1Backend.createVoteRequest(
                sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                action,
                "url",
                "description",
                new RelTime(durationUntilExpiration.toMillis * 1000),
                Some(env.environment.clock.now.add(durationUntilOffboardingEffectivity).toInstant),
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
            eventuallySucceeds() {
              sv.castVote(voteRequestCid, true, "url", "description")
            }
          }

          eventually(40.seconds) {
            sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet.asScala shouldBe Set(
              sv1Party,
              sv2Party,
              sv3Party,
            ).map(_.toProtoPrimitive)
            val mapping = sv1Backend.appState.participantAdminConnection
              .getPartyToParticipant(decentralizedSynchronizerId, sv1Backend.getDsoInfo().dsoParty)
              .futureValue
              .mapping
            mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
              sv1Backend.participantClient.id,
              sv2Backend.participantClient.id,
              sv3Backend.participantClient.id,
            )
            sv1Backend.appState.participantAdminConnection
              .getMediatorSynchronizerState(decentralizedSynchronizerId)
              .futureValue
              .mapping
              .active
              .forgetNE should contain theSameElementsAs Seq(
              sv1MediatorId,
              sv2MediatorId,
              sv3MediatorId,
            )
            sv1Backend.appState.participantAdminConnection
              .getSequencerSynchronizerState(decentralizedSynchronizerId)
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

        // Stop SV4
        clue("Stop SV4") {
          sv4Backend.stop()
          sv4ScanBackend.stop()
          sv4ValidatorBackend.stop()
        }
        (
          sv4ParticipantDump,
          sv4Name,
          (sv1Party, sv2Party, sv3Party, sv4Party),
          (sv1MediatorId, sv2MediatorId, sv3MediatorId, sv4MediatorId),
          (sv1SequencerId, sv2SequencerId, sv3SequencerId, sv4SequencerId),
        )
      }

      withCantonSvNodes(
        (None, None, None, Some(sv4Backend)),
        logSuffix = "sv4-reonboarding-new",
        svs123 = false,
        overrideSvDbsSuffix = Some("reonboard_new"),
        extraParticipantsConfigFileNames = Seq("standalone-participant-extra.conf"),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> validatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> s"participant_reonboard_new",
        ),
      )() {
        // Canton is sloooooooooooooooooooooooooooooooow
        eventuallySucceeds(timeUntilSuccess = 120.seconds) {
          sv4ReonboardBackend.participantClientWithAdminToken.health.status should be(
            NodeStatus.NotInitialized(true, Some(WaitingForId))
          )
        }
        better.files
          .File(dumpPath)
          .overwrite(
            sv4ParticipantDump.toJson.noSpaces
          )

        startAllSync(
          sv4ReonboardBackend,
          sv4ReonboardValidatorBackend,
          validatorLocalBackend,
        )

        val sv4PartyNew = sv4ReonboardBackend.getDsoInfo().svParty
        clue("partyId of re-onboarded sv4 is different") {
          sv4PartyNew should not be sv4Party
          sv4PartyNew.uid.identifier.unwrap should startWith("digital-asset-eng-4-reonboard")
          sv4PartyNew.uid.namespace should not be sv4Party.uid.namespace
        }

        sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet.asScala shouldBe Set(
          sv1Party,
          sv2Party,
          sv3Party,
          sv4PartyNew,
        ).map(_.toProtoPrimitive)

        sv1Backend
          .getDsoInfo()
          .dsoRules
          .payload
          .svs
          .asScala
          .get(sv4PartyNew.toProtoPrimitive)
          .value
          .name shouldBe sv4Name

        val mapping = sv1Backend.appState.participantAdminConnection
          .getPartyToParticipant(decentralizedSynchronizerId, sv1Backend.getDsoInfo().dsoParty)
          .futureValue
          .mapping
        mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
          sv1Backend.participantClient.id,
          sv2Backend.participantClient.id,
          sv3Backend.participantClient.id,
          sv4ReonboardBackend.participantClient.id,
        )
        val sv4MediatorIdNew =
          sv4ReonboardBackend.appState.localSynchronizerNode.value.mediatorAdminConnection.getMediatorId.futureValue
        val sv4SequencerIdNew =
          sv4ReonboardBackend.appState.localSynchronizerNode.value.sequencerAdminConnection.getSequencerId.futureValue
        sv1Backend.appState.participantAdminConnection
          .getMediatorSynchronizerState(decentralizedSynchronizerId)
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
          .getSequencerSynchronizerState(decentralizedSynchronizerId)
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
          new ARC_DsoRules(
            new SRARC_GrantFeaturedAppRight(
              new DsoRules_GrantFeaturedAppRight(sv4PartyNew.toProtoPrimitive)
            )
          )
        actAndCheck(
          "SV4 can create vote requests",
          sv4ReonboardBackend.createVoteRequest(
            sv4PartyNew.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
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

        clue("sv4 party is now the primary party of the new validator") {
          PartyId.tryFromProtoPrimitive(
            validatorLocalWalletClient.userStatus().party
          ) shouldBe sv4Party
        }

        val sv4PartyToParticipantMapping =
          validatorLocalBackend.appState.participantAdminConnection
            .getPartyToParticipant(
              decentralizedSynchronizerId,
              sv4Party,
            )
            .futureValue
            .mapping

        sv4PartyToParticipantMapping.participants.map(
          _.participantId
        ) should contain theSameElementsAs Seq(
          validatorLocalBackend.participantClient.id
        )

        validatorLocalBackend.participantClient.id.code shouldBe ParticipantId.Code
        validatorLocalBackend.participantClient.id.uid.identifier.unwrap shouldBe "validatorLocalNew"

        sv4WalletClient.userStatus().party shouldBe sv4PartyNew.toProtoPrimitive

        val originalAmulets: Range = 99 to 100
        clue("amulet balance is preserved from off-boarded sv4") {
          checkWalletAmount(
            sv4Party,
            validatorLocalWalletClient,
            (walletUsdToAmulet(originalAmulets.start), walletUsdToAmulet(originalAmulets.end)),
          )
        }

        actAndCheck(
          "sv4Party tap again on re-onboarded validator",
          validatorLocalWalletClient.tap(50.0),
        )(
          "balance updated",
          _ => {
            val expectedAmulets: Range = 49 to 50
            checkWalletAmount(
              sv4Party,
              validatorLocalWalletClient,
              (walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end)),
            )
          },
        )

        val (offerCid, _) =
          actAndCheck(
            "sv4Party creates transfer offer via the validator",
            validatorLocalWalletClient.createTransferOffer(
              sv4PartyNew,
              walletUsdToAmulet(148.0),
              "transfer recovered amulet back to SV",
              CantonTimestamp.now().plus(JDUration.ofMinutes(1)),
              UUID.randomUUID.toString,
            ),
          )(
            "sv4PartyNew sees transfer offer",
            _ => sv4WalletClient.listTransferOffers() should have length 1,
          )

        actAndCheck(
          "sv4PartyNew accepts transfer offer",
          sv4WalletClient.acceptTransferOffer(offerCid),
        )(
          "sv4PartyNew sees updated balance",
          _ => {
            sv4WalletClient.listTransferOffers() should have length 0
            val expectedAmulets: Range = 147 to 148
            checkWalletAmount(
              sv4PartyNew,
              sv4WalletClient,
              (walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end)),
            )
          },
        )
      }
    }
  }
}
