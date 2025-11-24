import scala.collection.mutable.ListBuffer

import cats.syntax.either._
import cats.syntax.functorFilter._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.console.{
  LocalInstanceReference,
  LocalMediatorReference,
  LocalSequencerReference,
}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.synchronizer.config.SynchronizerParametersConfig
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.console.commands.TopologyAdministrationGroup
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  StoredTopologyTransactions,
}
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.sequencing.{SequencerConnectionValidation, SequencerConnectionPoolDelays}
import com.digitalasset.canton.discard.Implicits.DiscardOps

println("Running canton bootstrap script...")

val domainParametersConfig = SynchronizerParametersConfig(
  alphaVersionSupport = true
)

def dropSignatures(tx: GenericSignedTopologyTransaction): GenericSignedTopologyTransaction = {
  tx.transaction.mapping match {
    case OwnerToKeyMapping(member, _) =>
      val signaturesToRemove =
        tx.signatures.forgetNE.filter(_.authorizingLongTermKey != member.namespace.fingerprint).map(_.authorizingLongTermKey)
      tx.removeSignatures(signaturesToRemove).get
    case _ => tx
  }
}

def staticParameters(sequencer: LocalInstanceReference) =
  domainParametersConfig
    .toStaticSynchronizerParameters(sequencer.config.crypto, ProtocolVersion.v34, NonNegativeInt.zero)
    .map(StaticSynchronizerParameters(_))
    .getOrElse(sys.error("whatever"))

def bootstrapDomainWithUnsignedKeys(
    name: String,
    sequencer: LocalSequencerReference,
    mediator: LocalMediatorReference,
    extraParticipant: LocalInstanceReference,
) = {
  // first synchronizer method
  val synchronizerName = name
  val mediatorsToSequencers = Seq(mediator).map(_ -> (Seq(sequencer), PositiveInt.one)).toMap
  val synchronizerThreshold = PositiveInt.one
  val staticSynchronizerParameters = staticParameters(sequencer)

  // second synchronizer method
  val sequencers =
    Seq(sequencer).groupBy(_.id).flatMap(_._2.headOption.toList).toList
  val synchronizerOwners = Seq(sv1Participant)
  val mediators = mediatorsToSequencers.keys.toSeq

  // run bootstrap method
  val synchronizerNamespace =
    DecentralizedNamespaceDefinition.computeNamespace(synchronizerOwners.map(_.namespace).toSet)
  val synchronizerId = SynchronizerId(
    UniqueIdentifier.tryCreate(synchronizerName, synchronizerNamespace)
  )
  val physicalSynchronizerId = PhysicalSynchronizerId(synchronizerId, ProtocolVersion.v34, NonNegativeInt.zero)

  val tempStoreForBootstrap = synchronizerOwners
    .map(
      _.topology.stores.create_temporary_topology_store(
        s"$synchronizerName-setup",
        staticSynchronizerParameters.protocolVersion,
      )
    )
    .headOption
    .getOrElse(sys.error("No synchronizer owners specified."))

  val identityTransactions =
    ((sequencers : Seq[com.digitalasset.canton.console.InstanceReference]) ++ mediators ++ synchronizerOwners ++ Seq(extraParticipant)).flatMap(
      _.topology.transactions.identity_transactions()
    )

  synchronizerOwners.foreach(
    _.topology.transactions.load(
      identityTransactions,
      store = tempStoreForBootstrap,
      ForceFlag.AlienMember,
    )
  )

  val (_, foundingTxs) =
    bootstrap.decentralized_namespace(
      synchronizerOwners,
      synchronizerThreshold,
      store = tempStoreForBootstrap,
    )

  val synchronizerGenesisTxs = synchronizerOwners.flatMap(
    _.topology.synchronizer_bootstrap.generate_genesis_topology(
      physicalSynchronizerId,
      synchronizerOwners.map(_.id.member),
      sequencers.map(_.id),
      mediators.map(_.id),
      store = tempStoreForBootstrap,
      mediatorThreshold = PositiveInt.one,
    )
  )

  val initialTopologyState = (identityTransactions ++ foundingTxs ++ synchronizerGenesisTxs)
    .mapFilter(_.selectOp[TopologyChangeOp.Replace])
    .distinct

  val merged =
    SignedTopologyTransactions.compact(initialTopologyState).map(_.updateIsProposal(false))

  val storedTopologySnapshot = StoredTopologyTransactions[TopologyChangeOp, TopologyMapping](
    merged.map(stored =>
      StoredTopologyTransaction(
        sequenced = SequencedTime(SignedTopologyTransaction.InitialTopologySequencingTime),
        validFrom = EffectiveTime(SignedTopologyTransaction.InitialTopologySequencingTime),
        validUntil = None,
        transaction = dropSignatures(stored),
        rejectionReason = None,
      )
    )
  ).toByteString(staticSynchronizerParameters.protocolVersion)

  sequencers
    .filterNot(_.health.initialized())
    .foreach(x =>
      x.setup
        .assign_from_genesis_state(storedTopologySnapshot, staticSynchronizerParameters)
        .discard
    )

  mediatorsToSequencers
    .filter(!_._1.health.initialized())
    .foreach { case (mediator, (mediatorSequencers, threshold)) =>
      mediator.setup.assign(
        physicalSynchronizerId,
        SequencerConnections.tryMany(
          mediatorSequencers
            .map(s => s.sequencerConnection.withAlias(SequencerAlias.tryCreate(s.name))),
          threshold,
          NonNegativeInt.zero,
          // This should match the SV app defaults so that the SV app does not try to change the connection.
          SubmissionRequestAmplification(
            PositiveInt.tryCreate(5),
            NonNegativeFiniteDuration.ofSeconds(10),
          ),
          SequencerConnectionPoolDelays.default
        ),
        // if we run bootstrap ourselves, we should have been able to reach the nodes
        // so we don't want the bootstrapping to fail spuriously here in the middle of
        // the setup
        SequencerConnectionValidation.Disabled,
      )
    }

  synchronizerOwners.foreach(
    _.topology.stores.drop_temporary_topology_store(tempStoreForBootstrap)
  )
}

bootstrapDomainWithUnsignedKeys(
  "global-domain",
  globalSequencerSv1,
  globalMediatorSv1,
  aliceParticipant,
)

// These user allocations are only there
// for local testing. Our tests allocate their own users.
println(s"Allocating users for local testing...")
val userParticipants = ListBuffer[(String, String)]()
Seq(
  (sv1Participant, "sv1"),
  (sv2Participant, "sv2"),
  (sv3Participant, "sv3"),
  (sv4Participant, "sv4"),
  (aliceParticipant, "alice_validator_user"),
  (bobParticipant, "bob_validator_user"),
  (splitwellParticipant, "splitwell_validator_user"),
).foreach { case (participant, user) =>
  participant.ledger_api.users.create(
    id = user,
    primaryParty = None,
    actAs = Set.empty,
    readAs = Set.empty,
    participantAdmin = true,
  )
  userParticipants.append(user -> participant.id.uid.toProtoPrimitive)
}
println(s"Writing down participant ids...")
val participantIdsContent =
  userParticipants.map(x => s"${x._1} ${x._2}").mkString(System.lineSeparator())
Files.write(
  Paths.get(System.getenv("CANTON_PARTICIPANTS_FILENAME")),
  participantIdsContent.getBytes(StandardCharsets.UTF_8),
)

// Inserting extra commands here (do not edit this line)

println(s"Collecting admin tokens...")
val adminTokensData = ListBuffer[(String, String)]()
participants.local.foreach(participant => {
  val adminToken = participant.underlying.map(_.adminTokenDispenser.getCurrentToken.secret).getOrElse("")
  val port = participant.config.ledgerApi.internalPort.get.unwrap
  adminTokensData.append(s"$port" -> adminToken)
})
val tokenFile = System.getenv("CANTON_TOKEN_FILENAME")
if (tokenFile == null) {
  sys.error("Environment variable CANTON_TOKEN_FILENAME was not set")
}
println(s"Writing admin tokens file to $tokenFile...")
val adminTokensContent =
  adminTokensData.map(x => s"${x._1} ${x._2}").mkString(System.lineSeparator())
Files.write(Paths.get(tokenFile), adminTokensContent.getBytes(StandardCharsets.UTF_8))

println("Canton bootstrap script done.")
