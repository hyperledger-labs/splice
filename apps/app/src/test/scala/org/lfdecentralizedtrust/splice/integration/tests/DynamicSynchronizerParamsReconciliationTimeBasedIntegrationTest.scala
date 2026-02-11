package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery

import java.time.Duration

class DynamicSynchronizerParamsReconciliationTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps +
            (InstanceName.tryCreate("sv1Local") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))) +
            (InstanceName.tryCreate("sv1") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))
                .copy(
                  preparationTimeRecordTimeTolerance = NonNegativeFiniteDuration.ofMinutes(1),
                  mediatorDeduplicationTimeout = NonNegativeFiniteDuration.ofMinutes(2),
                  // We disable it here to see that it gets changed to true by the reconciliation trigger of sv1Local
                  enableFreeConfirmationResponses = false,
                ))
        )
      })
      .withManualStart

  "ReconcileDynamicSynchronizerParametersTrigger can change preparationTimeRecordTimeTolerance" in {
    implicit env =>
      // Note: This test must run against a fresh Canton instance. Ideally we'd achieve this using withCanton
      // but that does not work with simtime as we try to access the remote clock before Canton is started
      // even with manualStart. On CI this is achieved by splitting it into a dedicated job.
      sv1Backend.startSync()

      val connectedDomain = sv1Backend.participantClient.synchronizers
        .list_connected()
        .find(_.synchronizerAlias == sv1Backend.config.domains.global.alias)
        .value
      val synchronizerId = connectedDomain.synchronizerId
      sv1Backend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(synchronizerId)
        .preparationTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofMinutes(1)
      sv1Backend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(synchronizerId)
        .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofMinutes(2)
      sv1Backend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(synchronizerId)
        .trafficControl
        .getOrElse(throw new RuntimeException("Traffic control parameters not found"))
        .freeConfirmationResponses shouldBe false
      sv1Backend.stop()

      sv1LocalBackend.startSync()
      eventually() {
        sv1Backend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(synchronizerId)
          .preparationTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofMinutes(1)
        sv1Backend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(synchronizerId)
          .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofHours(48)
        // Free confirmation responses are enabled via the ReconcileDynamicSynchronizerParametersTrigger when the SV starts, let's wait until this is the case.
        sv1Backend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(synchronizerId)
          .trafficControl
          .getOrElse(throw new RuntimeException("Traffic control parameters not found"))
          .freeConfirmationResponses shouldBe true
      }

      // We go slightly above 48h as time is not actually completely still in simtime, the microseconds still advance.
      advanceTime(Duration.ofHours(49))
      eventually() {
        sv1Backend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(synchronizerId)
          .preparationTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofHours(24)
        sv1Backend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(synchronizerId)
          .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofHours(48)
      }
      val txs = sv1Backend.participantClient.topology.synchronizer_parameters
        .list(
          store = TopologyStoreId.Synchronizer(synchronizerId),
          timeQuery = TimeQuery.Range(None, None),
        )
      txs should have length 3
      sv1LocalBackend.stop()
  }
}
