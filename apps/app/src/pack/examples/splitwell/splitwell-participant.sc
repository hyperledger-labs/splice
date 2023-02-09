val domainUrl = sys.env.get("DOMAIN_URL") match {
  case None => sys.error("Environment variable DOMAIN_URL was not set, set it to http://${targetcluster}.network.canton.global:5008")
  case Some(url) => url
}

println("Starting participant node")
splitwellParticipant.start()
println(s"Connecting self-hosted validator to the domain $domainUrl")
splitwellParticipant.domains.connect("global", domainUrl)
println(s"Creating validator user")
val validatorParty = splitwellParticipant.parties.enable("validator_user")
splitwellParticipant.ledger_api.users.create(
    id = "validator_user",
    actAs = Set(validatorParty.toLf),
    readAs = Set.empty,
    primaryParty = Some(validatorParty.toLf),
    participantAdmin = true,
)
