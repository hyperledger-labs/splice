package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{Port, PositiveInt}
import com.digitalasset.canton.crypto.KeyPurpose.Signing
import com.digitalasset.canton.crypto.SigningKeyUsage
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.transaction.OwnerToKeyMapping
import com.digitalasset.canton.topology.{Namespace, ParticipantId}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{PostgresAroundEach, ProcessTestUtil, WalletTestUtil}

class ValidatorOfflineRootNamespaceKeyIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with PostgresAroundEach
    with WalletTestUtil {

  override def usesDbs: Seq[String] =
    Seq(
      "participant_offline_key_generation",
      "alice_participant_offline_key",
    )

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
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
      val aliceValidatorParticipantClient = aliceValidatorBackend.participantClientWithAdminToken
      aliceValidatorParticipantClient.health.wait_for_ready_for_id()
      val delegatedNamespaceKey = aliceValidatorParticipantClient.keys.secret
        .generate_signing_key("namespace_delegate", usage = Set(SigningKeyUsage.Namespace))

      val (offlineGeneratedNamespace, rootNamespaceDelegation, delegationTopologyTransaction) =
        withCanton(
          Seq(
            // using this one to generate keys
            testResourcesPath / "standalone-participant-second-extra.conf"
          ),
          Seq(
          ),
          "offlineKeyGenerationParticipant",
          "SECOND_EXTRA_PARTICIPANT_DB" -> "participant_offline_key_generation",
          "SECOND_EXTRA_PARTICIPANT_ADMIN_USER" -> "not_used",
        ) {

          val adminApiConfig = ClientConfig(port = Port.tryCreate(27702))
          val offlineParticipantClient =
            new ParticipantClientReference(
              env,
              s"remote participant for key generation",
              RemoteParticipantConfig(
                adminApiConfig,
                ClientConfig(port = Port.tryCreate(27701)),
              ),
            )
          offlineParticipantClient.health.wait_for_ready_for_id()
          val offlineRootKey = offlineParticipantClient.keys.secret
            .generate_signing_key("rootSigningKey", SigningKeyUsage.NamespaceOnly)
          val offlineGeneratedNamespace = Namespace(offlineRootKey.fingerprint)
          offlineParticipantClient.topology.init_id(
            ParticipantId("offlineKeyGeneration", offlineGeneratedNamespace).uid,
            waitForReady = false,
          )
          val rootNamespaceDelegation =
            offlineParticipantClient.topology.namespace_delegations.propose_delegation(
              offlineGeneratedNamespace,
              offlineRootKey,
              isRootDelegation = true,
              signedBy = Seq(offlineGeneratedNamespace.fingerprint),
            )

          offlineParticipantClient.topology.owner_to_key_mappings.add_key(
            offlineRootKey.fingerprint,
            Signing,
            signedBy = Seq(offlineGeneratedNamespace.fingerprint),
          )

          val delegationTopologyTransaction =
            offlineParticipantClient.topology.namespace_delegations.propose_delegation(
              offlineGeneratedNamespace,
              delegatedNamespaceKey,
              isRootDelegation = false,
              signedBy = Seq(offlineRootKey.fingerprint),
            )

          (offlineGeneratedNamespace, rootNamespaceDelegation, delegationTopologyTransaction)

        }
      val signingKey = aliceValidatorParticipantClient.keys.secret
        .generate_signing_key(
          "signing",
          usage = Set(SigningKeyUsage.Protocol, SigningKeyUsage.SequencerAuthentication),
        )

      clue("participant is initialized with an offline root namespace key") {
        aliceValidatorParticipantClient.topology.init_id(
          ParticipantId("aliceValidator", offlineGeneratedNamespace).uid,
          waitForReady = false,
        )
        aliceValidatorParticipantClient.topology.transactions.load(
          Seq(rootNamespaceDelegation),
          "Authorized",
        )
        aliceValidatorParticipantClient.topology.transactions.load(
          Seq(delegationTopologyTransaction),
          "Authorized",
        )
        val encryptionKey =
          aliceValidatorParticipantClient.keys.secret.generate_encryption_key("ecryption")
        aliceValidatorParticipantClient.topology.owner_to_key_mappings.propose(
          OwnerToKeyMapping(
            aliceValidatorParticipantClient.id.member,
            NonEmpty(Seq, signingKey, encryptionKey),
          ),
          PositiveInt.one,
          signedBy = Seq(delegatedNamespaceKey.fingerprint, signingKey.fingerprint),
        )
        aliceValidatorBackend.startSync()
        val aliceParticipantAdminConnection =
          aliceValidatorBackend.appState.participantAdminConnection
        val participantId = aliceParticipantAdminConnection.identity().futureValue
        val validatorParticipantKeys = aliceParticipantAdminConnection
          .listMyKeys()
          .futureValue
        validatorParticipantKeys.exists(
          _.id == participantId.namespace.fingerprint
        ) shouldBe false
        clue("check tap works on validator") {
          onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          aliceWalletClient.tap(100.0)
        }
      }
    }
  }
}
