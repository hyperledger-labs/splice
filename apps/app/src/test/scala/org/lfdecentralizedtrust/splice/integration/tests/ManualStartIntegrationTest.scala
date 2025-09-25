package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.data.PruningSchedule
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{FullClientConfig, PositiveDurationSeconds}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPublicKey}
import com.digitalasset.canton.topology.{
  MediatorId,
  Member,
  Namespace,
  NodeIdentity,
  ParticipantId,
  SequencerId,
  UniqueIdentifier,
}
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, SpliceBackendConfig}
import org.lfdecentralizedtrust.splice.console.AppBackendReference
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.util.{StandaloneCanton, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.config.ParticipantPruningConfig

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ManualStartIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TriggerTestUtil
    with StandaloneCanton {

  override protected def runEventHistorySanityCheck: Boolean = false

  // This test runs against a temporary Canton instance, disable all automatic setup of the shared canton instance
  override lazy val resetRequiredTopologyState = false

  override lazy val dbsSuffix = "manual_start_2_" + UUID.randomUUID.toString.substring(0, 4)

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      // Do not use `simpleTopology4Svs`, because that one waits for shared canton nodes to be initialized
      // and then attempts to allocate users on the sv1 participant. This test doesn't care about the shared
      // canton nodes at all.
      .fromResources(Seq("simple-topology.conf"), this.getClass.getSimpleName)
      .withTrafficTopupsEnabled
      // This test makes sure apps can automatically initialize Canton instances.
      // The Splice apps in this test should therefore completely ignore the shared canton instances
      // (which are auto-initialized), and only use the manually started fresh Canton instances.
      .addConfigTransforms(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      // By default, alice validator connects to the splitwell domain. This test doesn't start the splitwell node.
      .addConfigTransform((_, conf) =>
        conf.copy(validatorApps =
          conf.validatorApps.updatedWith(InstanceName.tryCreate("aliceValidator")) {
            _.map { aliceValidatorConfig =>
              val withoutExtraDomains = aliceValidatorConfig.domains.copy(extra = Seq.empty)
              aliceValidatorConfig.copy(
                domains = withoutExtraDomains,
                participantPruningSchedule = Some(
                  ParticipantPruningConfig(
                    "0 0 * * * ?",
                    PositiveDurationSeconds.tryFromDuration(1.hours),
                    PositiveDurationSeconds.tryFromDuration(30.hours),
                  )
                ),
              )
            }
          }
        )
      )
      // Add a suffix to the canton identifiers to avoid metric conflicts with the shared canton nodes
      .withCantonNodeNameSuffix("StandaloneManualStart")
      // Splice apps should only start after the Canton instances are started
      .withManualStart
  }

  "Splice apps" should {
    "start with uninitialized Canton nodes" in { implicit env =>
      import env.executionContext
      def sequencerAdminConnection(name: String, config: SvAppBackendConfig) = {
        val loggerFactoryWithKey = loggerFactory.append("sequencer", name)
        new SequencerAdminConnection(
          FullClientConfig(port = config.localSynchronizerNode.value.sequencer.adminApi.port),
          env.environment.config.monitoring.logging.api,
          loggerFactoryWithKey,
          grpcClientMetrics,
          retryProvider,
        )
      }
      def mediatorAdminConnection(name: String, config: SvAppBackendConfig) = {
        val loggerFactoryWithKey = loggerFactory.append("mediator", name)
        new MediatorAdminConnection(
          FullClientConfig(port = config.localSynchronizerNode.value.mediator.adminApi.port),
          env.environment.config.monitoring.logging.api,
          loggerFactoryWithKey,
          grpcClientMetrics,
          retryProvider,
        )
      }

      withCantonSvNodes(
        adminUsersFromSvBackends =
          (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        logSuffix = s"manual-start",
        extraParticipantsConfigFileNames = Seq("standalone-participant-extra.conf"),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> ("participant_extra_" + dbsSuffix),
        ),
      )() {
        val allCnApps = Seq[AppBackendReference](
          sv1Backend,
          sv1ScanBackend,
          sv1ValidatorBackend,
          sv2Backend,
          sv2ScanBackend,
          sv2ValidatorBackend,
          aliceValidatorBackend,
        )

        val allTopologyConnections
            : Seq[(TopologyAdminConnection, UniqueIdentifier => Member & NodeIdentity)] = Seq(
          (participantAdminConnection("sv1", sv1Backend.config), ParticipantId.apply),
          (sequencerAdminConnection("sv1", sv1Backend.config), SequencerId.apply),
          (mediatorAdminConnection("sv1", sv1Backend.config), MediatorId.apply),
          (participantAdminConnection("sv2", sv2Backend.config), ParticipantId.apply),
          (sequencerAdminConnection("sv2", sv2Backend.config), SequencerId.apply),
          (mediatorAdminConnection("sv2", sv2Backend.config), MediatorId.apply),
          (participantAdminConnection("alice", aliceValidatorBackend.config), ParticipantId.apply),
        )

        clue("All Canton nodes are running but have no identity") {
          def assertHasNoIdentity(connection: TopologyAdminConnection) = {
            // Eventually, because the query to the server will fail while the server is still starting up
            // Long timeout because Canton is slow to start up
            eventually(timeUntilSuccess = 60.seconds) {
              val idResult = connection.getIdOption().futureValue
              idResult.initialized shouldBe false
              idResult.uniqueIdentifier shouldBe None
            }
          }

          allTopologyConnections.foreach(x => assertHasNoIdentity(x._1))
        }

        clue("Starting all Splice apps") {
          startAllSync(allCnApps*)
        }

        clue(
          "All Canton nodes have identity and signing keys different from their namespace keys"
        ) {
          allTopologyConnections.foreach(assertSigningKeysDifferent.tupled)
        }

        clue("Alice participant pruning config has been changed") {
          aliceValidatorBackend.participantClient.pruning.get_schedule() shouldBe Some(
            PruningSchedule(
              "0 0 * * * ?",
              PositiveDurationSeconds.tryFromDuration(1.hours),
              PositiveDurationSeconds.tryFromDuration(30.hours),
            )
          )
        }

        clue("SV1 and SV2 have configured amplification on the mediator sequencer connection") {
          Seq(
            mediatorAdminConnection("sv1", sv1Backend.config),
            mediatorAdminConnection("sv2", sv2Backend.config),
          ).map { mediatorConnection =>
            val sequencerConnections =
              mediatorConnection
                .getSequencerConnections()
                .futureValue
                .value
            sequencerConnections.connections.size shouldBe 1
            sequencerConnections.sequencerTrustThreshold shouldBe PositiveInt.tryCreate(1)
            sequencerConnections.submissionRequestAmplification shouldBe SvAppBackendConfig.DefaultMediatorSequencerRequestAmplification
            // otherwise we get log warnings
            mediatorConnection.close()
          }
        }

        // A most basic check to see whether the network is functional
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        actAndCheck(
          "Alice taps some coin",
          aliceWalletClient.tap(100),
        )(
          "Alice's balance has increased",
          _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(50),
        )

        // Check whether restarting apps doesn't mess up the already running canton nodes
        clue("Stopping all Splice apps") {
          stopAllAsync(allCnApps*).futureValue
        }
        clue("Starting all Splice apps") {
          startAllSync(allCnApps*)
        }

        // Wait for automation to start and the user to be reported as onboarded
        clue("Alice is reported as onboarded") {
          waitForWalletUser(aliceWalletClient)
        }

        actAndCheck(
          "Alice taps some coin again",
          aliceWalletClient.tap(200),
        )(
          "Alice's balance has increased",
          _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(150),
        )

        clue("Cleaning up") {
          allTopologyConnections.foreach(_._1.close())
        }
      }
    }

    "start with initialized Canton participants and fix signing keys if needed" in { implicit env =>
      withCantonSvNodes(
        adminUsersFromSvBackends =
          (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        logSuffix = s"manual-start",
        extraParticipantsConfigFileNames = Seq("standalone-participant-extra.conf"),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> ("participant_extra_" + dbsSuffix),
        ),
      )() {

        val allCnApps = Seq[AppBackendReference](
          sv1Backend,
          sv1ScanBackend,
          sv1ValidatorBackend,
          sv2Backend,
          sv2ScanBackend,
          sv2ValidatorBackend,
          aliceValidatorBackend,
        )

        val allParticipantAdminConnectionsExSv1 = Seq(
          (
            participantAdminConnection("sv2", sv2Backend.config),
            sv2Backend.config.cantonIdentifierConfig.value.participant,
          ),
          (
            participantAdminConnection("alice", aliceValidatorBackend.config),
            aliceValidatorBackend.config.cantonIdentifierConfig.value.participant,
          ),
        )
        val allParticipantAdminConnections = Seq(
          (
            participantAdminConnection("sv1", sv1Backend.config),
            sv1Backend.config.cantonIdentifierConfig.value.participant,
          )
        ) ++ allParticipantAdminConnectionsExSv1

        clue("Initialize all participants, with signing keys reusal") {
          def initializeWithKeyReuse(connection: ParticipantAdminConnection, name: String): Unit = {
            // Eventually, because the query to the server will fail while the server is still starting up
            // Long timeout because Canton is slow to start up
            eventually(timeUntilSuccess = 60.seconds) {

              val signingKey =
                connection.generateKeyPair("signing", SigningKeyUsage.All).futureValue
              val encryptionKey = connection.generateEncryptionKeyPair("encryption").futureValue
              // this is part of the wrong part! we should not be reusing this key
              val namespace = Namespace(signingKey.id)
              val uid = UniqueIdentifier.tryCreate(name, signingKey.id.toProtoPrimitive)
              val nodeId = ParticipantId.apply(uid)

              // Setting node identity
              connection.initId(nodeId).futureValue

              // Adding root certificate
              connection
                .ensureNamespaceDelegation(
                  namespace = namespace,
                  target = signingKey,
                  isRootDelegation = true,
                  retryFor = RetryFor.Automation,
                )
                .futureValue

              // Adding owner-to-key mappings
              connection
                .ensureInitialOwnerToKeyMapping(
                  member = nodeId,
                  keys = NonEmpty(Seq, signingKey, encryptionKey),
                  retryFor = RetryFor.Automation,
                )
                .futureValue
            }

          }
          // SV1's original init is more complicated and things get messy when we mess with the participant before starting that;
          // so we're skipping sv1 here to not overcomplicate the test
          allParticipantAdminConnectionsExSv1.foreach(initializeWithKeyReuse.tupled)
        }

        clue("Starting all Splice apps") {
          startAllSync(allCnApps*)
        }

        clue(
          "All Canton nodes have identity and signing keys different from their namespace keys"
        ) {
          eventually() {
            allParticipantAdminConnections.foreach(x =>
              assertSigningKeysDifferent(x._1, ParticipantId.apply)
            )
          }
        }
        clue("Cleaning up") {
          allParticipantAdminConnections.foreach(_._1.close())
        }
      }
    }
  }

  private def assertSigningKeysDifferent(
      connection: TopologyAdminConnection,
      nodeIdentity: UniqueIdentifier => Member & NodeIdentity,
  ): Unit = {
    eventually() {
      val idResult = connection.getIdOption().futureValue
      idResult.initialized shouldBe true
      val id = nodeIdentity(idResult.uniqueIdentifier.value)
      val ownerToKeyMappings =
        connection.listOwnerToKeyMapping(id).futureValue.map(_.mapping)
      ownerToKeyMappings.foreach { mapping =>
        mapping.keys.foreach {
          case key: SigningPublicKey =>
            key.id should not be id.namespace.fingerprint
          case _ =>
        }
      }
    }
  }

  private def participantAdminConnection(name: String, config: SpliceBackendConfig)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    import env.executionContext
    val loggerFactoryWithKey = loggerFactory.append("participant", name)
    new ParticipantAdminConnection(
      FullClientConfig(port = config.participantClient.adminApi.port),
      env.environment.config.monitoring.logging.api,
      loggerFactoryWithKey,
      grpcClientMetrics,
      retryProvider,
    )
  }
}
