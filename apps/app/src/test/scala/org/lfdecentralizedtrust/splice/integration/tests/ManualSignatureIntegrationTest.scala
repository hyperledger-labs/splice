package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.topology.ListOwnerToKeyMappingResult
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.crypto.SigningPublicKey
import com.digitalasset.canton.topology.Namespace
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

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart
      // .withTrafficTopupsDisabled
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
      .withSequencerConnectionsFromScanDisabled()
  }

  "synchronizer" should {

    def checkLatestKeysAreSigned(
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

    "rotate OTK keys that are not signed for SVs" in { implicit env =>
      clue("sv1 starts") {
        sv1Backend.startSync()
      }

      eventuallySucceeds() {
        val synchronizerId = sv1Backend.participantClientWithAdminToken.synchronizers.id_of(
          sv1Backend.config.domains.global.alias
        )
        val store = TopologyStoreId.Synchronizer(synchronizerId)
        val otks = sv1Backend.participantClientWithAdminToken.topology.owner_to_key_mappings
          .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))

        clue("keys are rotated for sv1's sequencer") {
          checkLatestKeysAreSigned(otks, sv1Backend.sequencerClient.namespace)
        }
        clue("keys are rotated for sv1's mediator") {
          checkLatestKeysAreSigned(otks, sv1Backend.mediatorClient.namespace)
        }
        clue("keys are rotated for sv1's participant") {
          checkLatestKeysAreSigned(otks, sv1Backend.participantClient.namespace)
        }
      }
    }

    "rotate OTK keys that are not signed for validators" in { implicit env =>
      clue("Initalize the DSO") {
        initDsoWithSv1Only()
      }

      clue("Alice validator starts") {
        aliceValidatorBackend.start()
      }

      eventuallySucceeds() {
        val synchronizerId =
          aliceValidatorBackend.participantClientWithAdminToken.synchronizers.id_of(
            aliceValidatorBackend.config.domains.global.alias
          )
        val store = TopologyStoreId.Synchronizer(synchronizerId)
        val otks =
          aliceValidatorBackend.participantClientWithAdminToken.topology.owner_to_key_mappings
            .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))

        clue("keys are rotated for alice validator's participant") {
          checkLatestKeysAreSigned(otks, aliceValidatorBackend.participantClient.namespace)
        }
      }

      clue("Alice validator shuts down") {
        eventuallySucceeds() {
          aliceValidatorBackend.stop()
        }
      }
    }
  }
}
