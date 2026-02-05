package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.topology.ListOwnerToKeyMappingResult
import com.digitalasset.canton.crypto.SigningPublicKey
import com.digitalasset.canton.topology.Namespace
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import scala.concurrent.duration.DurationInt

class ManualSignatureIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart
      .withoutAliceValidatorConnectingToSplitwell
      .withSequencerConnectionsFromScanDisabled()
  }

  "synchronizer" should {
    def checkLatestOTKKeysAreSigned(
        otks: Seq[ListOwnerToKeyMappingResult],
        namespace: Namespace,
    ): Unit = {
      val otksForNs = otks
        .filter(_.item.member.namespace == namespace)
      val latestKeys = otksForNs
        .maxBy(_.context.serial)
        .item
        .keys
        .filter {
          case _: SigningPublicKey => true
          case _ => false
        }
        .map(_.id)
        .distinct
      val signatures = otksForNs.flatMap(_.context.signedBy).distinct
      latestKeys.diff(signatures) shouldBe empty
    }

    "rotate SV' OTK mapping keys that are not signed." in { implicit env =>
      clue("sv1 starts.") {
        sv1Backend.startSync()
      }

      eventuallySucceeds() {
        val synchronizerId = sv1Backend.participantClientWithAdminToken.synchronizers.id_of(
          sv1Backend.config.domains.global.alias
        )
        val store = TopologyStoreId.Synchronizer(synchronizerId)
        val otks = sv1Backend.participantClientWithAdminToken.topology.owner_to_key_mappings
          .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))

        clue("keys are rotated for sv1's sequencer.") {
          checkLatestOTKKeysAreSigned(otks, sv1Backend.sequencerClient.namespace)
        }
        clue("keys are rotated for sv1's mediator.") {
          checkLatestOTKKeysAreSigned(otks, sv1Backend.mediatorClient.namespace)
        }
        clue("keys are rotated for sv1's participant.") {
          checkLatestOTKKeysAreSigned(otks, sv1Backend.participantClient.namespace)
        }
      }

      clue("sv1 shuts down.") {
        eventuallySucceeds() {
          sv1Backend.stop()
        }
      }
    }

    "rotate validators' OTK mapping keys that are not signed." in { implicit env =>
      clue("Initialize the DSO") {
        initDsoWithSv1Only()
      }
      clue("Alice validator starts.") {
        aliceValidatorBackend.start()
      }

      eventuallySucceeds(40.seconds) {
        val synchronizerId =
          aliceValidatorBackend.participantClientWithAdminToken.synchronizers.id_of(
            aliceValidatorBackend.config.domains.global.alias
          )
        val store = TopologyStoreId.Synchronizer(synchronizerId)
        val otks =
          aliceValidatorBackend.participantClientWithAdminToken.topology.owner_to_key_mappings
            .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))

        clue("keys are rotated for alice validator's participant.") {
          checkLatestOTKKeysAreSigned(otks, aliceValidatorBackend.participantClient.namespace)
        }
      }

      clue("Alice validator and sv1 shut down.") {
        eventuallySucceeds() {
          aliceValidatorBackend.stop()
          sv1Backend.stop()
        }
      }
    }
  }
}
