println("Bootstrapping CN participant...")

val domainLabel = "svc_domain"
val domainUrl = System.getProperty("DOMAIN_URL", "http://canton-domain:5008")

if (`svc_participant`.domains.list_connected().isEmpty) {
    println("No registered domains, so connecting to the SVC domain for the first time...")

    `svc_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`svc_participant`.domains.active(domainLabel))

    println("Executing self ping for connection verification...")
    `svc_participant`.health.ping(`svc_participant`)
}

val svcUserName = System.getenv("CN_APP_SVC_LEDGER_API_AUTH_USER_NAME")
if (svcUserName == null) {
  sys.error("Environment variable CN_APP_SVC_LEDGER_API_AUTH_USER_NAME does not exist")
}

val scanUserName = System.getenv("CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME")
if (scanUserName == null) {
  sys.error("Environment variable CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME does not exist")
}

val directoryUserName = System.getenv("CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME")
if (directoryUserName == null) {
  sys.error("Environment variable CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME does not exist")
}

// User name may contain characters not allowed in party names
val svcPartyName = "svc"

println(s"Creating svc user $svcUserName...")
val svcParty = `svc_participant`.parties.enable(svcPartyName)
`svc_participant`.ledger_api.users.create(
  id = svcUserName,
  actAs = Set(svcParty.toLf),
  readAs = Set.empty,
  primaryParty = Some(svcParty.toLf),
  participantAdmin = true,
)

println(s"Creating scan user $scanUserName...")
// Shares party with SVC but user can only read
`svc_participant`.ledger_api.users.create(
  id = scanUserName,
  actAs = Set.empty,
  readAs = Set(svcParty.toLf),
  primaryParty = Some(svcParty.toLf),
  participantAdmin = true,
)

println(s"Creating directory user $directoryUserName...")
// Shares party with SVC
`svc_participant`.ledger_api.users.create(
  id = directoryUserName,
  actAs = Set(svcParty.toLf),
  readAs = Set.empty,
  primaryParty = Some(svcParty.toLf),
  participantAdmin = true,
)

println("Bootstrapped CN participant!")
