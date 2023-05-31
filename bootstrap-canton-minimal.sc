import scala.collection.mutable.ListBuffer

import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.digitalasset.canton.console.ParticipantReference
import com.digitalasset.canton.topology.PartyId

println("Running canton bootstrap script...")

Seq(global, splitwell, splitwellUpgrade).foreach(domain =>
  // Reduce participant response timeout to force faster timeouts in particular around time changes in simtime.
  // See #3186
  domain.service.update_dynamic_domain_parameters(
    _.update(participantResponseTimeout = NonNegativeFiniteDuration.ofSeconds(10))
  )
)

println("Connecting all participants to global domain...")
participants.all.domains.connect_local(global)
println("Connecting splitwell, aliceParticipant (for both alice & bob) to splitwell domain...")
Seq(aliceParticipant, splitwellParticipant).foreach(
  _.domains.connect_local(splitwell)
)
// We only connect splitwell by default since we want to simulate users connecting gradually to the domain.
println("Connecting splitwell to upgraded domain...")
splitwellParticipant.domains.connect_local(splitwellUpgrade)

def createUser(
    participant: ParticipantReference,
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

println(s"Allocating validator service users and SVC user...")
Seq(
  (aliceParticipant, "alice_validator_user"),
  (aliceParticipant, "bob_validator_user"),
  (splitwellParticipant, "splitwell_validator_user"),
  (splitwellParticipant, "splitwell_provider"),
).foreach { case (participant, user) =>
  createUser(participant, user)
}

val svcParty = createUser(svcParticipant, "svc_shared_service_user")

// These users are created for BootstrapTest and start-backends-for-local-frontend-testing.sh to work.

val sv1Party = createUser(svcParticipant, "sv1_validator_user")
svcParticipant.ledger_api.users.create(
  id = "sv1",
  primaryParty = Some(sv1Party),
  actAs = Set(sv1Party, svcParty),
  readAs = Set.empty,
  participantAdmin = true,
)

Seq(
  ("sv2", sv2Participant),
  ("sv3", sv3Participant),
  ("sv4", sv4Participant),
).foreach { case (sv, participant) =>
  val svParty = createUser(participant, s"${sv}_validator_user")
  participant.ledger_api.users.create(
    id = sv,
    actAs = Set(svParty),
    primaryParty = Some(svParty),
    readAs = Set(),
    participantAdmin = true,
  )
}

svcParticipant.ledger_api.users.create(
  id = "directory_provider",
  actAs = Set(svcParty),
  primaryParty = Some(svcParty),
  readAs = Set(),
  participantAdmin = false,
)

println(s"Collecting admin tokens...")
val adminTokensData = ListBuffer[(String, String)]()
participants.local.foreach(participant => {
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
