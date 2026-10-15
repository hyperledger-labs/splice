import scala.collection.mutable.ListBuffer

import cats.syntax.either._
import cats.syntax.functorFilter._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.console.{
  LocalInstanceReference,
  LocalMediatorReference,
  LocalSequencerReference,
}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.synchronizer.config.SynchronizerParametersConfig
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.admin.api.client.data.TrafficControlParameters
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.version.ProtocolVersion

println("Running canton bootstrap script...")

val tokenFile = System.getenv("CANTON_TOKEN_FILENAME")
if (tokenFile == null) {
  sys.error("Environment variable CANTON_TOKEN_FILENAME was not set")
}

val domainParametersConfig = SynchronizerParametersConfig(
  alphaVersionSupport = true,
  // simtime does not work with non-zero topology change delay so we overwrite it matching the Canton tests
  topologyChangeDelay = Some(if (tokenFile == "canton-simtime.tokens") NonNegativeFiniteDuration.Zero else NonNegativeFiniteDuration.ofMillis(250)),
)

def staticParameters(sequencer: LocalInstanceReference) =
  domainParametersConfig
    .toStaticSynchronizerParameters(sequencer.config.crypto, ProtocolVersion.v35, NonNegativeInt.zero)
    .map(StaticSynchronizerParameters(_))
    .getOrElse(sys.error("whatever"))

// The base rate is Canton's default unless start-canton.sh sets it; -t sets it to zero for the sync operator tests.
val splitwellMaxBaseTrafficAmount = Option(System.getenv("SPLITWELL_MAX_BASE_TRAFFIC_AMOUNT"))
  .filter(_.nonEmpty)
  .map(amount => NonNegativeLong.tryCreate(amount.toLong))
  .getOrElse(NonNegativeLong.tryCreate(10 * 20 * 1024))

// Canton's own defaults apart from the base rate. A member only gets a traffic state, and so can
// only be granted extra traffic, on a synchronizer that has traffic control enabled.
val defaultTrafficControlParameters = TrafficControlParameters(
  maxBaseTrafficAmount = splitwellMaxBaseTrafficAmount,
  readVsWriteScalingFactor = PositiveInt.tryCreate(200),
  maxBaseTrafficAccumulationDuration = PositiveFiniteDuration.ofMinutes(10),
  setBalanceRequestSubmissionWindowSize = PositiveFiniteDuration.ofMinutes(5),
  enforceRateLimiting = true,
  baseEventCost = NonNegativeLong.zero,
  freeConfirmationResponses = false,
)

def bootstrapOtherDomain(
    name: String,
    sequencer: LocalSequencerReference,
    mediator: LocalMediatorReference,
    // Traffic control is off by default here: only synchronizers that stand in for a dedicated one
    // need it, and enabling it everywhere would change what every other test sequences against.
    enableTrafficControl: Boolean = false,
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
        trafficControl =
          if (enableTrafficControl) Some(defaultTrafficControlParameters) else parameters.trafficControl,
      ),
    signedBy = Some(sequencer.id.uid.namespace.fingerprint),
    // This is test code so just force the change.
    force = ForceFlags(ForceFlag.PreparationTimeRecordTimeToleranceIncrease),
  )
}

// splitwell is the only non-global synchronizer a sync operator can be pointed at today, so it
// carries traffic control; see apps/app/src/test/resources/sync-operator-topology.conf.
bootstrapOtherDomain("splitwell", splitwellSequencer, splitwellMediator, enableTrafficControl = true)
bootstrapOtherDomain("splitwellUpgrade", splitwellUpgradeSequencer, splitwellUpgradeMediator)

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
println(s"Writing admin tokens file to $tokenFile...")
val adminTokensContent =
  adminTokensData.map(x => s"${x._1} ${x._2}").mkString(System.lineSeparator())
Files.write(Paths.get(tokenFile), adminTokensContent.getBytes(StandardCharsets.UTF_8))

println("Canton bootstrap script done.")
