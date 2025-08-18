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

println("Running canton bootstrap script...")

val domainParametersConfig = SynchronizerParametersConfig(
  alphaVersionSupport = true
)

def staticParameters(sequencer: LocalInstanceReference) =
  domainParametersConfig
    .toStaticSynchronizerParameters(sequencer.config.crypto, ProtocolVersion.v34, NonNegativeInt.zero)
    .map(StaticSynchronizerParameters(_))
    .getOrElse(sys.error("whatever"))

def bootstrapOtherDomain(
    name: String,
    sequencer: LocalSequencerReference,
    mediator: LocalMediatorReference,
) = {
  bootstrap.synchronizer(
    name,
    synchronizerOwners = Seq(sequencer),
    sequencers = Seq(sequencer),
    mediators = Seq(mediator),
    synchronizerThreshold = PositiveInt.one,
    staticSynchronizerParameters = staticParameters(sequencer),
  )
  // For some stupid reason bootstrap.domain does not allow changing the dynamic domain parameters
  // so we overwrite it here.
  val synchronizerId = sequencer.synchronizer_id
  // Align the reconciliation interval and catchup config with what our triggers set.
  // This doesn't really matter for splitwell but it matters for the soft synchronizer upgrade test.
  sequencer.topology.synchronizer_parameters.propose_update(
    synchronizerId,
    parameters =>
      parameters.update(
        reconciliationInterval = PositiveDurationSeconds.ofMinutes(30),
        acsCommitmentsCatchUpParameters = Some(
          AcsCommitmentsCatchUpParameters(
            catchUpIntervalSkip = PositiveInt.tryCreate(24),
            nrIntervalsToTriggerCatchUp = PositiveInt.tryCreate(2),
          )
        ),
        preparationTimeRecordTimeTolerance = NonNegativeFiniteDuration.ofHours(24),
        mediatorDeduplicationTimeout = NonNegativeFiniteDuration.ofHours(48),
      ),
    signedBy = Some(sequencer.id.uid.namespace.fingerprint),
    // This is test code so just force the change.
    force = ForceFlags(ForceFlag.PreparationTimeRecordTimeToleranceIncrease),
  )
}

Seq(
  ("splitwell", splitwellSequencer, splitwellMediator),
  ("splitwellUpgrade", splitwellUpgradeSequencer, splitwellUpgradeMediator),
).foreach((bootstrapOtherDomain _).tupled)

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
