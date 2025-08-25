package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

class ManualSignatureIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override lazy val resetRequiredTopologyState = false

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart
      .withSequencerConnectionsFromScanDisabled()
  }

  "synchronizer" should {

    "rotate signatures" in { implicit env =>
      sv1Backend.startSync()
      eventually() {
        val synchronizerId = sv1Backend.participantClientWithAdminToken.synchronizers.id_of(
          sv1Backend.config.domains.global.alias
        )
        // FIXME: SV: add checks for mediator, sequencer and participant signatures + add checks
        val store = TopologyStoreId.Synchronizer(synchronizerId)
        sv1Backend.participantClientWithAdminToken.topology.owner_to_key_mappings
          .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))
          .filter(
            _.item.member.toProtoPrimitive == sv1Backend.sequencerClient.name
          ) should not be empty
        // FIXME: Validator: add checks for validator signatures + add checks
      }
    }

  }
}
