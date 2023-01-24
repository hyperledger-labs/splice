println("===============================")
println("Bootstrapping splitwise provider participant...")
println("===============================")

val domainLabel = "global"
val domainUrl = "http://canton-domain:5008"

println(s"Connecting to domain $domainUrl...")
if (`splitwise_participant`.domains.list_connected().isEmpty) {
    println("No registered domains, so connecting to the CN domain for the first time...")

    `splitwise_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`splitwise_participant`.domains.active(domainLabel))

    println("Executing self ping for connection verification...")
    `splitwise_participant`.health.ping(`splitwise_participant`)
}

val validatorServiceUserName = System.getenv("CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_USER_NAME")
if (validatorServiceUserName == null) {
  sys.error("Environment variable CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_USER_NAME does not exist")
}

// Ensure there's an service Party
val validatorServiceParty = "splitwise_validator_service_user"
if (`splitwise_participant`.parties.list(validatorServiceParty).isEmpty) {
   println(s"Enabling validator party $validatorServiceParty...")
   `splitwise_participant`.parties.enable(validatorServiceParty)
}
val validatorParty = `splitwise_participant`.parties.list(validatorServiceParty).headOption.get.party
println(s"Using validator party $validatorParty..")

if (`splitwise_participant`.ledger_api.users.list(validatorServiceUserName).users.isEmpty) {
  `splitwise_participant`.ledger_api.users.create(
    id = validatorServiceUserName,
    actAs = Set(validatorParty.toLf),
    readAs = Set.empty,
    primaryParty = Some(validatorParty.toLf),
    participantAdmin = true,
  )
}

println("===============================")
println("Bootstrapped splitwise provider participant!")
println("===============================")
