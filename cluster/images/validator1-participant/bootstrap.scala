println("Bootstrapping validator1 participant...")

val domainLabel = "canton-network"
val domainUrl = System.getProperty("DOMAIN_URL", "http://canton-domain:5008")

println(s"Connecting to domain $domainUrl...")
if (`validator1_participant`.domains.list_connected().isEmpty) {
    println("No registered domains, so connecting to the CN domain for the first time...")

    `validator1_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`validator1_participant`.domains.active(domainLabel))

    println("Executing self ping for connection verification...")
    `validator1_participant`.health.ping(`validator1_participant`)
}

val validatorServiceUserName = System.getenv("CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME")
if (validatorServiceUserName == null) {
  sys.error("Environment variable CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME does not exist")
}

val walletServiceUserName = System.getenv("CN_APP_WALLET_LEDGER_API_AUTH_USER_NAME")
if (walletServiceUserName == null) {
  sys.error("Environment variable CN_APP_WALLET_LEDGER_API_AUTH_USER_NAME does not exist")
}

// User name may contain characters not allowed in party names
val validatorServicePartyName = "validator1_validator_service_user"

println(s"Creating validator user $validatorServiceUserName...")
val validatorParty = `validator1_participant`.parties.enable(validatorServicePartyName)
`validator1_participant`.ledger_api.users.create(
  id = validatorServiceUserName,
  actAs = Set(validatorParty.toLf),
  readAs = Set.empty,
  primaryParty = Some(validatorParty.toLf),
  participantAdmin = true,
)

println("Bootstrapped validator1 participant!")
