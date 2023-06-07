import scala.collection.mutable.ListBuffer

import cats.syntax.either._
import cats.syntax.functorFilter._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.digitalasset.canton.console.{LocalInstanceReferenceX, LocalParticipantReferenceX}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyChangeOpX
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}

println("Running canton bootstrap script...")

println("Bootstrapping global domain")

val domainParametersConfig = DomainParametersConfig(
  protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
  devVersionSupport = true,
  uniqueContractKeys = false,
)

def staticParameters(sequencer: LocalInstanceReferenceX) =
  domainParametersConfig
    .toStaticDomainParameters(sequencer.config.crypto)
    .flatMap(StaticDomainParameters(_).leftMap(_.toString))
    .getOrElse(sys.error("whatever"))

def identityTransactions(node: LocalInstanceReferenceX): Seq[GenericSignedTopologyTransactionX] = {
  val codes = Set(NamespaceDelegationX.code, OwnerToKeyMappingX.code)
  node.topology.transactions
    .list(filterAuthorizedKey = Some(node.id.uid.namespace.fingerprint))
    .result
    .map(_.transaction)
    .filter(x => codes.contains(x.transaction.mapping.code))
}

// The identity transactions for the 3 nodes that are part of the bootstrap transactions.
val identityTxs: Seq[GenericSignedTopologyTransactionX] =
  Seq(sv1Participant, globalSequencerSv1, globalMediatorSv1).flatMap(identityTransactions(_))

// The unionspace definition containing sv1Participant as the only member.
val unionspace = sv1Participant.topology.unionspaces.propose(
  Set(sv1Participant.id.uid.namespace.fingerprint),
  threshold = PositiveInt.one,
  signedBy = Some(sv1Participant.id.uid.namespace.fingerprint),
)

val globalDomainId = DomainId(
  UniqueIdentifier(
    Identifier.tryCreate("global-domain"),
    unionspace.transaction.mapping.unionspace,
  )
)

val domainParameterState = sv1Participant.topology.domain_parameters.propose(
  globalDomainId,
  DynamicDomainParameters.initialXValues(consoleEnvironment.environment.clock, ProtocolVersion.dev),
  signedBy = Some(sv1Participant.id.uid.namespace.fingerprint),
)

// The mediator state signed by both the unionspace owner, sv1Participant and the mediator. Note
// that due to lack of validation in Canton nothing breaks if we only use one of the two.
val mediatorState = {
  val txs = Seq(sv1Participant, globalMediatorSv1).map(node =>
    node.topology.mediators.propose(
      globalDomainId,
      threshold = PositiveInt.one,
      active = Seq(globalMediatorSv1.id),
      signedBy = Some(node.id.uid.namespace.fingerprint),
    )
  )
  txs.reduce[GenericSignedTopologyTransactionX] { case (a, b) =>
    a.addSignatures(b.signatures.toSeq)
  }
}

// The sequencer state signed by both the unionspace owner, sv1Participant and the mediator. Note
// that due to lack of validation in Canton nothing breaks if we only use one of the two.
val sequencerState = {
  val txs = Seq(sv1Participant, globalSequencerSv1).map(node =>
    node.topology.sequencers.propose(
      globalDomainId,
      threshold = PositiveInt.one,
      active = Seq(globalSequencerSv1.id),
      signedBy = Some(node.id.uid.namespace.fingerprint),
    )
  )
  txs.reduce[GenericSignedTopologyTransactionX] { case (a, b) =>
    a.addSignatures(b.signatures.toSeq)
  }
}

// The transactions required for bootstrapping
val bootstrapTransactions =
  (identityTxs ++ Seq(domainParameterState, mediatorState, sequencerState): Seq[
    GenericSignedTopologyTransactionX
  ]).mapFilter(_.selectOp[TopologyChangeOpX.Replace])

globalSequencerSv1.setup.assign_from_beginning(
  bootstrapTransactions,
  staticParameters(globalSequencerSv1),
)

globalMediatorSv1.setup.assign(
  globalDomainId,
  staticParameters(globalSequencerSv1),
  globalSequencerSv1.id,
  SequencerConnections.single(globalSequencerSv1.sequencerConnection),
)

println("Bootstrapped global domain")

Seq(
  ("splitwell", splitwellSequencer, splitwellMediator),
  ("splitwellUpgrade", splitwellUpgradeSequencer, splitwellUpgradeMediator),
).foreach { case (name, sequencer, mediator) =>
  sequencer.domain.bootstrap(
    name,
    staticParameters(sequencer),
    domainOwners = Seq(sequencer, mediator),
    sequencers = Seq(sequencer),
    mediators = Seq(mediator),
  )
}

println("Connecting all participants to global domain...")
participantsX.local.foreach(
  _.domains.connect_local(globalSequencerSv1, alias = Some(DomainAlias.tryCreate("global")))
)

println("Connecting splitwell, alice & bob participant to splitwell domain...")
Seq(aliceParticipant, bobParticipant, splitwellParticipant).foreach(
  _.domains.connect_local(splitwellSequencer, alias = Some(DomainAlias.tryCreate("splitwell")))
)
// We only connect splitwell by default since we want to simulate users connecting gradually to the domain.
println("Connecting splitwell to upgraded domain...")
splitwellParticipant.domains.connect_local(
  splitwellUpgradeSequencer,
  alias = Some(DomainAlias.tryCreate("splitwellUpgrade")),
)

println(s"Allocating users for local testing...")
// These users are created for BootstrapTest and start-backends-for-local-frontend-testing.sh to work.
def createUser(
    participant: LocalParticipantReferenceX,
    user: String,
    additionalActAsParties: Set[PartyId] = Set(),
    readAsParties: Set[PartyId] = Set(),
) = {
  val party = participant.ledger_api.parties.allocate(user, user).party
  participant.ledger_api.users.create(
    id = user,
    actAs = Set(party) ++ additionalActAsParties,
    primaryParty = Some(party),
    readAs = readAsParties,
    participantAdmin = true,
  )
  party
}

Seq(
  (aliceParticipant, "alice_validator_user"),
  (splitwellParticipant, "splitwell_validator_user"),
  (splitwellParticipant, "splitwell_provider"),
).foreach { case (participant, user) =>
  createUser(participant, user)
}

val svcParty = createUser(sv1Participant, "svc_shared_service_user")

val sv1Party = createUser(sv1Participant, "sv1_validator_user")
sv1Participant.ledger_api.users.create(
  id = "sv1",
  primaryParty = Some(sv1Party),
  actAs = Set(sv1Party, svcParty),
  readAs = Set.empty,
  participantAdmin = true,
)

sv1Participant.ledger_api.users.create(
  id = "directory_provider",
  actAs = Set(svcParty),
  primaryParty = Some(svcParty),
  readAs = Set(),
  participantAdmin = false,
)

println(s"Collecting admin tokens...")
val adminTokensData = ListBuffer[(String, String)]()
participantsX.local.foreach(participant => {
  val adminToken = participant.underlying.map(_.adminToken.secret).getOrElse("")
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
