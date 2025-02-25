package org.lfdecentralizedtrust.splice.integration.tests.offlinekey

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{Port, PositiveInt}
import com.digitalasset.canton.console.InstanceReference
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPublicKey}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.transaction.{
  NamespaceDelegation,
  OwnerToKeyMapping,
  SignedTopologyTransaction,
  TopologyChangeOp,
}
import com.digitalasset.canton.topology.{Namespace, ParticipantId}
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.environment.SpliceConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{PostgresAroundEach, ProcessTestUtil}

import scala.util.Random

trait OfflineRootNamespaceKeyUtil extends PostgresAroundEach {
  this: IntegrationTest & ProcessTestUtil =>

  override def usesDbs: Seq[String] = {
    randomDbs
  }

  private val randomDbs =
    Iterator.fill(9)(s"random_${Random.alphanumeric.take(20).mkString.toLowerCase()}").toSeq
  private val randomDbsIterator = Iterator.from(randomDbs)
  private def nextAvailableDb = randomDbsIterator.next()

  def instanceHasNoRootNamespaceKey(participant: InstanceReference): Unit = {
    val instanceId = participant.id
    val participantKeys = participant.keys.secret.list()
    participantKeys.exists(
      _.id == instanceId.namespace.fingerprint
    ) shouldBe false
  }

  def setupOfflineNodeWithKey(
      delegateKey: SigningPublicKey
  )(implicit env: SpliceConsoleEnvironment): (
      Namespace,
      SignedTopologyTransaction[TopologyChangeOp, NamespaceDelegation],
      SignedTopologyTransaction[TopologyChangeOp, NamespaceDelegation],
  ) = {

    withCanton(
      Seq(
        // using this one to generate keys
        testResourcesPath / "standalone-participant-second-extra.conf"
      ),
      Seq(
      ),
      "offlineKeyGenerationParticipant",
      "SECOND_EXTRA_PARTICIPANT_DB" -> nextAvailableDb,
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

      val delegationTopologyTransaction =
        offlineParticipantClient.topology.namespace_delegations.propose_delegation(
          offlineGeneratedNamespace,
          delegateKey,
          isRootDelegation = false,
          signedBy = Seq(offlineRootKey.fingerprint),
        )

      (offlineGeneratedNamespace, rootNamespaceDelegation, delegationTopologyTransaction)

    }
  }

  def initializeInstanceWithOfflineRootNamespaceKey(name: String, node: InstanceReference)(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    node.health.wait_for_ready_for_id()
    val delegatedNamespaceKey = node.keys.secret
      .generate_signing_key("namespace_delegate", usage = Set(SigningKeyUsage.Namespace))
    val signingKey = node.keys.secret
      .generate_signing_key(
        "signing",
        usage = Set(SigningKeyUsage.Protocol, SigningKeyUsage.SequencerAuthentication),
      )

    val (offlineGeneratedNamespace, rootNamespaceDelegation, delegationTopologyTransaction) =
      setupOfflineNodeWithKey(delegatedNamespaceKey)
    node.topology.init_id(
      ParticipantId(name, offlineGeneratedNamespace).uid,
      waitForReady = false,
    )
    node.topology.transactions.load(
      Seq(rootNamespaceDelegation),
      "Authorized",
    )
    node.topology.transactions.load(
      Seq(delegationTopologyTransaction),
      "Authorized",
    )
    val encryptionKey =
      node.keys.secret.generate_encryption_key("ecryption")
    node.topology.owner_to_key_mappings.propose(
      OwnerToKeyMapping(
        node.id.member,
        NonEmpty(Seq, signingKey, encryptionKey),
      ),
      PositiveInt.one,
      signedBy = Seq(delegatedNamespaceKey.fingerprint, signingKey.fingerprint),
    )
  }
}
