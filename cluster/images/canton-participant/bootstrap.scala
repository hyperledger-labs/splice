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

def getCnAppLedgerApiAuthUserNameFromEnv(app: String) = {
  val envVar = s"CN_APP_${app}_LEDGER_API_AUTH_USER_NAME"
  sys.env.get(envVar)
    .getOrElse(sys.error(s"Environment variable ${envVar} does not exist"))
}

val svcUserName = getCnAppLedgerApiAuthUserNameFromEnv("SVC")

val scanUserName = getCnAppLedgerApiAuthUserNameFromEnv("SCAN")

val directoryUserName = getCnAppLedgerApiAuthUserNameFromEnv("DIRECTORY")

val svUserNames = Seq("SV1", "SV2", "SV3", "SV4").map(getCnAppLedgerApiAuthUserNameFromEnv)

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

// User name may contain characters not allowed in party names
val svPartyNames = Seq("sv1", "sv2", "sv3", "sv4")

svPartyNames.zip(svUserNames).foreach({ case (svPartyName, svUserName) => {
  println(s"Creating sv user $svUserName...")
  val svParty = `svc_participant`.parties.enable(svPartyName)
  `svc_participant`.ledger_api.users.create(
    id = svUserName,
    actAs = Set(svParty.toLf),
    readAs = Set(svcParty.toLf),
    primaryParty = Some(svParty.toLf),
    participantAdmin = true,
  )
}})

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
