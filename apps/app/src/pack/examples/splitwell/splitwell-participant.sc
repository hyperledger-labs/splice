import com.digitalasset.canton.topology.PartyId
val domainUrl = sys.env.get("DOMAIN_URL") match {
  case None => sys.error("Environment variable DOMAIN_URL was not set, set it to http://${targetcluster}.network.canton.global:5008")
  case Some(url) => url
}

println("Starting participant node")
splitwellParticipant.start()
println(s"Connecting self-hosted validator to the domain $domainUrl")
splitwellParticipant.domains.connect("global", domainUrl)
println(s"Creating validator user")
val validatorParty = PartyId.tryFromProtoPrimitive(splitwellParticipant.ledger_api.parties.allocate("validator_user","validator_user").party)
splitwellParticipant.ledger_api.users.create(
    id = "validator_user",
    actAs = Set(validatorParty),
    readAs = Set.empty,
    primaryParty = Some(validatorParty),
    participantAdmin = true,
)
