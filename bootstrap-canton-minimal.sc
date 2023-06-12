import scala.collection.mutable.ListBuffer

import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

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

println(s"Allocating users for local testing...")
// These users are created for BootstrapTest and start-backends-for-local-frontend-testing.sh to work.
Seq(
  (svcParticipant, "sv1"),
  (aliceParticipant, "alice_validator_user"),
  (splitwellParticipant, "splitwell_validator_user"),
).foreach { case (participant, user) =>
  participant.ledger_api.users.create(
    id = user,
    primaryParty = None,
    actAs = Set.empty,
    readAs = Set.empty,
    participantAdmin = true,
  )
}

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
