import scala.util.Try

def log(msg: String) = println(s"BOOTSTRAP: $msg")

log("===============================")
log("Bootstrapping validator1 participant...")
log("===============================")

val domainLabel = "global"
val domainUrl = "http://canton-domain:5008"

log(s"Connecting to domain $domainUrl...")
if (`validator1_participant`.domains.list_connected().isEmpty) {
    log("No registered domains, so connecting to the CN domain for the first time...")

    `validator1_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`validator1_participant`.domains.active(domainLabel))

    log("Executing self ping for connection verification...")
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

// Ensure there's a validator party

def getUser(userName: String) =
 Try(`validator1_participant`.ledger_api.users.get(userName)).toOption

getUser(validatorServiceUserName).getOrElse({
  val validatorServicePartyName = "validator1_validator_service_user"
  log(s"Enabling party $validatorServicePartyName...")
  val validatorParty = `validator1_participant`.parties.enable(validatorServicePartyName)

  log(s"Creating validator user $validatorServiceUserName...")
  `validator1_participant`.ledger_api.users.create(
    id = validatorServiceUserName,
    actAs = Set(validatorParty.toLf),
    readAs = Set.empty,
    primaryParty = Some(validatorParty.toLf),
    participantAdmin = true,
  )
})

log("===============================")
log("Bootstrapped validator1 participant!")
log("===============================")
