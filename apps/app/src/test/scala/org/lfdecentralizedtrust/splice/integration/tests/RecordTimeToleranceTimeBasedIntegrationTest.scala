package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.store.TimeQuery
import java.time.Duration

class RecordTimeToleranceTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
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
                  submissionTimeRecordTimeTolerance = NonNegativeFiniteDuration.ofMinutes(1),
                  mediatorDeduplicationTimeout = NonNegativeFiniteDuration.ofMinutes(2),
                ))
        )
      })
      .withManualStart

  "ReconcileDynamicDomainParametersTrigger can change submissionTimeRecordTimeTolerance" in {
    implicit env =>
      // Note: This test must run against a fresh Canton instance. Ideally we'd achieve this using withCanton
      // but that does not work with simtime as we try to access the remote clock before Canton is started
      // even with manualStart. On CI this is achieved by splitting it into a dedicated job.
      sv1Backend.startSync()

      val connectedDomain = sv1Backend.participantClient.domains
        .list_connected()
        .find(_.domainAlias == sv1Backend.config.domains.global.alias)
        .value
      val domainId = connectedDomain.domainId
      sv1Backend.participantClient.topology.domain_parameters
        .get_dynamic_domain_parameters(domainId)
        .submissionTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofMinutes(1)
      sv1Backend.participantClient.topology.domain_parameters
        .get_dynamic_domain_parameters(domainId)
        .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofMinutes(2)
      sv1Backend.stop()
      sv1LocalBackend.startSync()
      eventually() {
        sv1Backend.participantClient.topology.domain_parameters
          .get_dynamic_domain_parameters(domainId)
          .submissionTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofMinutes(1)
        sv1Backend.participantClient.topology.domain_parameters
          .get_dynamic_domain_parameters(domainId)
          .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofHours(48)
      }
      // We go slightly above 48h as time is not actually completely still in simtime, the microseconds still advance.
      advanceTime(Duration.ofHours(49))
      eventually() {
        sv1Backend.participantClient.topology.domain_parameters
          .get_dynamic_domain_parameters(domainId)
          .submissionTimeRecordTimeTolerance shouldBe NonNegativeFiniteDuration.ofHours(24)
        sv1Backend.participantClient.topology.domain_parameters
          .get_dynamic_domain_parameters(domainId)
          .mediatorDeduplicationTimeout shouldBe NonNegativeFiniteDuration.ofHours(48)
      }
      val txs = sv1Backend.participantClient.topology.domain_parameters
        .list(filterStore = domainId.filterString, timeQuery = TimeQuery.Range(None, None))
      txs should have length (3)
      sv1LocalBackend.stop()
  }
}
