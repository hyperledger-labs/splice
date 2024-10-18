package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
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
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ClientConfig, NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ManualStartIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TriggerTestUtil
    with StandaloneCanton {

  // This test runs against a temporary Canton instance, disable all automatic setup of the shared canton instance
  override lazy val resetRequiredTopologyState = false

  override lazy val dbsSuffix = "manual_start_2_" + UUID.randomUUID.toString.substring(0, 4)

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
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
                domains = withoutExtraDomains
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
      import env.environment.scheduler
      import env.executionContext
      val grpcClientMetrics: GrpcClientMetrics = new DamlGrpcClientMetrics(
        NoOpMetricsFactory,
        "testing",
      )
      val retryProvider = new RetryProvider(
        loggerFactory,
        ProcessingTimeout(),
        new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds)),
        NoOpMetricsFactory,
      )
      def participantAdminConnection(name: String, config: SpliceBackendConfig) = {
        val loggerFactoryWithKey = loggerFactory.append("participant", name)
        new ParticipantAdminConnection(
          ClientConfig(port = config.participantClient.adminApi.port),
          env.environment.config.monitoring.logging.api,
          loggerFactoryWithKey,
          grpcClientMetrics,
          retryProvider,
        )
      }
      def sequencerAdminConnection(name: String, config: SvAppBackendConfig) = {
        val loggerFactoryWithKey = loggerFactory.append("sequencer", name)
        new SequencerAdminConnection(
          ClientConfig(port = config.localSynchronizerNode.value.sequencer.adminApi.port),
          env.environment.config.monitoring.logging.api,
          loggerFactoryWithKey,
          grpcClientMetrics,
          retryProvider,
        )
      }
      def mediatorAdminConnection(name: String, config: SvAppBackendConfig) = {
        val loggerFactoryWithKey = loggerFactory.append("mediator", name)
        new MediatorAdminConnection(
          ClientConfig(port = config.localSynchronizerNode.value.mediator.adminApi.port),
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
        extraParticipantsConfigFileName = Some("standalone-participant-extra.conf"),
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

        val allTopologyConnections = Seq(
          participantAdminConnection("sv1", sv1Backend.config),
          sequencerAdminConnection("sv1", sv1Backend.config),
          mediatorAdminConnection("sv1", sv1Backend.config),
          participantAdminConnection("sv2", sv2Backend.config),
          sequencerAdminConnection("sv2", sv2Backend.config),
          mediatorAdminConnection("sv2", sv2Backend.config),
          participantAdminConnection("alice", aliceValidatorBackend.config),
        )

        clue("All Canton nodes are running but have no identity") {
          def assertHasNoIdentity(connection: TopologyAdminConnection) = {
            // Eventually, because the query to the server will fail while the server is still starting up
            // Long timeout because Canton is slow to start up
            eventually(timeUntilSuccess = 60.seconds) {
              val id = connection.getIdOption().futureValue
              id.initialized shouldBe false
              id.uniqueIdentifier shouldBe None
            }
          }

          allTopologyConnections.foreach(assertHasNoIdentity)
        }

        clue("Starting all Splice apps") {
          startAllSync(allCnApps*)
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

        actAndCheck(
          "Alice taps some coin again",
          aliceWalletClient.tap(200),
        )(
          "Alice's balance has increased",
          _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(150),
        )

        clue("Cleaning up") {
          allTopologyConnections.foreach(_.close())
          retryProvider.close()
        }
      }
    }
  }

}
