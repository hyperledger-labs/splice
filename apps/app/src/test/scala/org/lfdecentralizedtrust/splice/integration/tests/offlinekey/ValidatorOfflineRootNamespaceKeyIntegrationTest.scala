package org.lfdecentralizedtrust.splice.integration.tests.offlinekey

import com.digitalasset.canton.crypto.SigningKeyUsage
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.DelegationRestriction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{PostgresAroundEach, ProcessTestUtil, WalletTestUtil}

// TODO(#917) use KMS for this test; whoever goes through the trouble of offline root namespace keys probably also uses KMS
class ValidatorOfflineRootNamespaceKeyIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with OfflineRootNamespaceKeyUtil
    with PostgresAroundEach
    with WalletTestUtil {

  // It will fail to find the validator of alice_validator_wallet_user
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def usesDbs: Seq[String] = {
    super.usesDbs ++ Seq(
      "alice_participant_offline_key",
      "alice_participant_offline_key_2",
    )
  }

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // we start the participants during the test so we cannot pre-allocate
      .withPreSetup(_ => ())
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq("aliceValidator"))
      .addConfigTransforms(
        (_, config) =>
          // use a fresh participant to ensure a fresh deployment so we can validate the topology state
          ConfigTransforms.bumpSomeValidatorAppPortsBy(22_000, Seq("aliceValidator"))(config),
        (_, config) =>
          // use a fresh participant to ensure a fresh deployment so we can validate the topology state
          ConfigTransforms.bumpSomeWalletClientPortsBy(22_000, Seq("aliceWallet"))(config),
      )
      // By default, alice validator connects to the splitwell domain. This test doesn't start the splitwell node.
      .withoutAliceValidatorConnectingToSplitwell
      .withTrafficTopupsDisabled
      .withManualStart

  "start validator an offline stored root namespace key" in { implicit env =>
    initDsoWithSv1Only()
    withCanton(
      Seq(
        // used by the alice validator
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(
      ),
      "aliceValidatorExtra",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> "alice_participant_offline_key",
    ) {
      clue("participant is initialized with an offline root namespace key") {
        val aliceValidatorParticipantClient = aliceValidatorBackend.participantClientWithAdminToken
        initializeInstanceWithOfflineRootNamespaceKey(
          "aliceValidator",
          aliceValidatorParticipantClient,
        )
      }
      aliceValidatorBackend.startSync()
      instanceHasNoRootNamespaceKey(aliceValidatorBackend.participantClientWithAdminToken)
      clue("check tap works on validator") {
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)
      }
    }
  }

  "start validator normally and remove the root namespace key later on" in { implicit env =>
    initDsoWithSv1Only()
    withCanton(
      Seq(
        // used by the alice validator
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(
      ),
      "aliceValidatorExtra",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> "alice_participant_offline_key_2",
    ) {
      clue("participant starts and works normally") {
        aliceValidatorBackend.startSync()
        instanceHasRootNamespaceKey(aliceValidatorBackend.participantClientWithAdminToken)
      }
      val aliceParticipant = aliceValidatorBackend.participantClientWithAdminToken
      val rootKeyId = aliceParticipant.topology.namespace_delegations
        .list(TopologyStoreId.Authorized)
        .filter(_.item.restriction == DelegationRestriction.CanSignAllMappings)(0)
        .item
        .target
        .id
      val delegateKey = clue("generate a delegated namespace key") {
        aliceParticipant.keys.secret.generate_signing_key(
          "namespace_delegate",
          usage = Set(SigningKeyUsage.Namespace),
        )
      }
      actAndCheck(
        s"set up namespace key delegation to ${delegateKey.id}", {
          aliceParticipant.topology.namespace_delegations.propose_delegation(
            aliceParticipant.id.namespace,
            delegateKey,
            delegationRestriction = DelegationRestriction.CanSignAllButNamespaceDelegations,
          )
        },
      )(
        "the delegation is set up",
        { _ =>
          aliceParticipant.topology.namespace_delegations
            .list(TopologyStoreId.Authorized)
            .map(_.item.target.id) should contain(delegateKey.id)
        },
      )
      actAndCheck()(
        s"remove the root namespace key ($rootKeyId)", {
          aliceParticipant.keys.secret.delete(rootKeyId, true)
        },
      )(
        "the root namespace key is removed",
        { _ =>
          instanceHasNoRootNamespaceKey(aliceValidatorBackend.participantClientWithAdminToken)
        },
      )
      val aliceUserParty = clue("check that onboard and tap still works on validator") {
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)
        aliceUserParty
      }
      clue(s"The party mapping for $aliceUserParty was signed with the delegate key") {
        val domainId =
          aliceValidatorBackend.participantClient.synchronizers.list_connected()(0).synchronizerId
        aliceParticipant.topology.party_to_participant_mappings
          .list(domainId, filterParty = aliceUserParty.toProtoPrimitive)(0)
          .context
          .signedBy
          .toSeq shouldBe Seq(delegateKey.id)
      }
    }
  }
}
