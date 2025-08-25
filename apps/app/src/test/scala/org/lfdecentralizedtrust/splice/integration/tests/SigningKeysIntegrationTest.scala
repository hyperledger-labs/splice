package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.InstanceReference
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPublicKey}
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.transaction.OwnerToKeyMapping
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ScanAppBackendReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.environment.SpliceConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.integration.tests.offlinekey.OfflineRootNamespaceKeyUtil
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}

class SigningKeysIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil
    with OfflineRootNamespaceKeyUtil {
  override def usesDbs: Seq[String] =
    super[StandaloneCanton].usesDbs ++ super[OfflineRootNamespaceKeyUtil].usesDbs

  override def dbsSuffix: String = "outdated_otk_key"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  private val cantonNameSuffix: String = getClass.getSimpleName

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(cantonNameSuffix)
      .withPreSetup(_ => ())
      .addConfigTransforms(
        (_, config) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(config),
        (_, config) => ConfigTransforms.bumpCantonPortsBy(22_000)(config),
      )
      .withCantonNodeNameSuffix(cantonNameSuffix)
      .withTrafficTopupsDisabled
      .withManualStart

  "start svs with offline root namespace keys" in { implicit env =>
    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
      logSuffix = "outdated-otk-keys",
      sv4 = false,
    )() {
      initSvNodesWithOutdatedOTK(sv1Backend, sv1ScanBackend)
      initSvNodesWithOutdatedOTK(sv2Backend, sv2ScanBackend)
    }
  }

  private def initSvNodesWithOutdatedOTK(
      backend: SvAppBackendReference,
      scan: ScanAppBackendReference,
  )(implicit env: SpliceConsoleEnvironment): Unit = {
    val participantClient = backend.participantClientWithAdminToken
    val sequencerClient = backend.sequencerClient
    val mediatorClient = backend.mediatorClient
    val faultyParticipantKey = initializeInstanceWithOutdatedOTK(
      s"${backend.name}$cantonNameSuffix",
      participantClient,
    )
    val faultySequencertKey = initializeInstanceWithOutdatedOTK(
      s"${backend.name}$cantonNameSuffix",
      sequencerClient,
    )
    val faultyMediatorKey = initializeInstanceWithOutdatedOTK(
      s"${backend.name}$cantonNameSuffix",
      mediatorClient,
    )
    scan.start()
    backend.startSync()
    scan.waitForInitialization()
    instanceHasCorrectOTKs(participantClient, faultyParticipantKey)
    instanceHasCorrectOTKs(sequencerClient, faultySequencertKey)
    instanceHasCorrectOTKs(mediatorClient, faultyMediatorKey)
  }

  private def instanceHasCorrectOTKs(
      participant: InstanceReference,
      key: SigningPublicKey,
  ): Unit = {
    val participantKeys = participant.keys.secret.list()
    participantKeys.exists(
      _.publicKey.id == key.id
    ) shouldBe false
  }

  private def initializeInstanceWithOutdatedOTK(
      name: String,
      node: InstanceReference,
  )(implicit env: SpliceConsoleEnvironment): SigningPublicKey = {
    node.health.wait_for_ready_for_id()
    val delegatedNamespaceKey = node.keys.secret
      .generate_signing_key("namespace_delegate", usage = Set(SigningKeyUsage.Namespace))
    val (offlineGeneratedNamespace, rootNamespaceDelegation, delegationTopologyTransaction) =
      setupOfflineNodeWithKey(delegatedNamespaceKey)
    node.topology.init_id_from_uid(
      ParticipantId(name, offlineGeneratedNamespace).uid,
      waitForReady = false,
      delegations = Seq(rootNamespaceDelegation, delegationTopologyTransaction),
    )
    val signingKey = node.keys.secret
      .generate_signing_key(
        "signing",
        usage = Set(SigningKeyUsage.Protocol, SigningKeyUsage.SequencerAuthentication),
      )
    val faultySigningKey = node.keys.secret
      .generate_signing_key(
        "signing",
        usage = Set(SigningKeyUsage.Protocol, SigningKeyUsage.SequencerAuthentication),
      )
    val encryptionKey = {
      node.keys.secret.generate_encryption_key("encryption")
    }
//    node.topology.owner_to_key_mappings.propose(
//      OwnerToKeyMapping(
//        node.id.member,
//        NonEmpty(Seq, encryptionKey, faultySigningKey),
//      ),
//      signedBy = Seq(delegatedNamespaceKey.fingerprint, faultySigningKey.fingerprint),
//    )
    node.topology.owner_to_key_mappings.propose(
      OwnerToKeyMapping(
        node.id.member,
        NonEmpty(Seq, signingKey, encryptionKey),
      ),
      serial = Some(PositiveInt.one),
      signedBy = Seq(delegatedNamespaceKey.fingerprint, signingKey.fingerprint),
    )
    // node.topology.ow
    faultySigningKey
  }

}
