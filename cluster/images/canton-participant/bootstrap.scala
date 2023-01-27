import scala.util.Try

def log(msg: String) = println(s"BOOTSTRAP: $msg")

log("===============================")
log("Bootstrapping CN participant...")
log("===============================")

val domainLabel = "global"
val domainUrl = "http://canton-domain:5008"

if (`svc_participant`.domains.list_connected().isEmpty) {
    log("No registered domains, so connecting to the SVC domain for the first time...")

    `svc_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`svc_participant`.domains.active(domainLabel))
} else {
    log("Domain already registered.")
}

def getCnAppLedgerApiAuthUserNameFromEnv(app: String) = {
  val envVar = s"CN_APP_${app}_LEDGER_API_AUTH_USER_NAME"
  sys.env.get(envVar)
    .getOrElse(sys.error(s"Environment variable ${envVar} does not exist"))
}

val svcUserName = getCnAppLedgerApiAuthUserNameFromEnv("SVC")

val scanUserName = getCnAppLedgerApiAuthUserNameFromEnv("SCAN")

val directoryUserName = getCnAppLedgerApiAuthUserNameFromEnv("DIRECTORY")


// Ensure there's an SVC Party


def getUser(userName: String) =
 Try(`svc_participant`.ledger_api.users.get(userName)).toOption

def userExists(userName: String) =
 getUser(userName).isDefined


val svcUser = getUser(svcUserName).getOrElse({
   val svcPartyName = "svc"
   log(s"Enabling svc party $svcPartyName...")

   val svcParty = `svc_participant`.parties.enable(svcPartyName)

   log(s"Creating svc user $svcUserName...")
   `svc_participant`.ledger_api.users.create(
     id = svcUserName,
     actAs = Set(svcParty.toLf),
     readAs = Set.empty,
     primaryParty = Some(svcParty.toLf),
     participantAdmin = true,
   )

   getUser(svcUserName).get
})

log(s"svcUser is: $svcUser")

val svcParty = svcUser.primaryParty.get

log(s"svcUser primaryParty is: $svcParty")


if (!userExists(scanUserName)) {
  log(s"Creating scan user $scanUserName...")
  // Shares party with SVC but user can only read
  `svc_participant`.ledger_api.users.create(
    id = scanUserName,
    actAs = Set.empty,
    readAs = Set(svcParty),
    primaryParty = Some(svcParty),
    participantAdmin = false,
  )
} else {
  log(s"Using existing scan user $scanUserName...")
}

if (!userExists(directoryUserName)) {
  log(s"Creating directory user $directoryUserName...")
  // Shares party with SVC
  `svc_participant`.ledger_api.users.create(
    id = directoryUserName,
    actAs = Set(svcParty),
    readAs = Set.empty,
    primaryParty = Some(svcParty),
    participantAdmin = true,
  )
} else {
  log(s"Using existing directory user $directoryUserName...")
}


// SV Parties

val svUserNames = Seq("SV1", "SV2", "SV3", "SV4").map(getCnAppLedgerApiAuthUserNameFromEnv)
val svPartyNames = Seq("sv1", "sv2", "sv3", "sv4")

svPartyNames.zip(svUserNames).foreach({ case (svPartyName, svUserName) => {
  log(s"Checking for sv user $svUserName...")
  getUser(svUserName).getOrElse({
    log(s"Creating sv user $svUserName...")

    val svParty = `svc_participant`.parties.enable(svPartyName)
    val foundConsortium = svPartyName == "sv1" // we configure sv1 to `found-consortium`

    `svc_participant`.ledger_api.users.create(
      id = svUserName,
      actAs =
        // the SV app will revoke the "act as svcParty" right at the end of its init
        if (foundConsortium) Set(svParty.toLf, svcParty)
        else Set(svParty.toLf),
      readAs = Set(svcParty),
      primaryParty = Some(svParty.toLf),
      participantAdmin = true,
    )
  })
}})

log("===============================")
log("Bootstrapped CN participant!")
log("===============================")
