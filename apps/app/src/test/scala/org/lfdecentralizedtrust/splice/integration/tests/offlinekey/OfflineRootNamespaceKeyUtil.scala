package org.lfdecentralizedtrust.splice.integration.tests.offlinekey

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.config.RequireTypes.{Port, PositiveInt}
import com.digitalasset.canton.console.InstanceReference
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPublicKey}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.transaction.{
  DelegationRestriction,
  NamespaceDelegation,
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
    checkInstanceHasRootNamespaceKey(participant) shouldBe false
  }

  def instanceHasRootNamespaceKey(participant: InstanceReference): Unit = {
    checkInstanceHasRootNamespaceKey(participant) shouldBe true
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

      val adminApiConfig = FullClientConfig(port = Port.tryCreate(27702))
      val offlineParticipantClient =
        new ParticipantClientReference(
          env,
          s"remote participant for key generation",
          RemoteParticipantConfig(
            adminApiConfig,
            FullClientConfig(port = Port.tryCreate(27701)),
          ),
        )
      offlineParticipantClient.health.wait_for_ready_for_id()
      val offlineRootKey = offlineParticipantClient.keys.secret
        .generate_signing_key("rootSigningKey", SigningKeyUsage.NamespaceOnly)
      val offlineGeneratedNamespace = Namespace(offlineRootKey.fingerprint)
      offlineParticipantClient.topology.init_id_from_uid(
        ParticipantId("offlineKeyGeneration", offlineGeneratedNamespace).uid,
        waitForReady = false,
      )
      val rootNamespaceDelegation =
        offlineParticipantClient.topology.namespace_delegations.propose_delegation(
          offlineGeneratedNamespace,
          offlineRootKey,
          delegationRestriction = DelegationRestriction.CanSignAllMappings,
          signedBy = Seq(offlineGeneratedNamespace.fingerprint),
        )

      val delegationTopologyTransaction =
        offlineParticipantClient.topology.namespace_delegations.propose_delegation(
          offlineGeneratedNamespace,
          delegateKey,
          delegationRestriction = DelegationRestriction.CanSignAllButNamespaceDelegations,
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
    node.topology.init_id_from_uid(
      ParticipantId(name, offlineGeneratedNamespace).uid,
      waitForReady = false,
      delegations = Seq(rootNamespaceDelegation, delegationTopologyTransaction),
    )
    val encryptionKey =
      node.keys.secret.generate_encryption_key("ecryption")
    node.topology.owner_to_key_mappings.propose(
      node.id.member,
      NonEmpty(Seq, signingKey, encryptionKey),
      Some(PositiveInt.one),
      signedBy = Seq(delegatedNamespaceKey.fingerprint, signingKey.fingerprint),
    )
  }

  private def checkInstanceHasRootNamespaceKey(participant: InstanceReference): Boolean = {
    val instanceId = participant.id
    val participantKeys = participant.keys.secret.list()
    participantKeys.exists(
      _.id == instanceId.namespace.fingerprint
    )
  }

}
