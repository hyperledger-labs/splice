import $file.tools

def main() {
  tools.connectGlobalDomain(`svc_participant`)

  val svcUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv("SVC")

  val scanUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv("SCAN")

  val directoryUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv("DIRECTORY")

  // Ensure there's an SVC Party

  val svcUser = tools.ensureParticipantUser(`svc_participant`, svcUserName, {
     val svcParty = `svc_participant`.parties.enable("svc")

     `svc_participant`.ledger_api.users.create(
       id = svcUserName,
       actAs = Set(svcParty.toLf),
       readAs = Set.empty,
       primaryParty = Some(svcParty.toLf),
       participantAdmin = true,
     )
  })

  val svcParty = svcUser.primaryParty.get

  tools.ensureParticipantUser(`svc_participant`, scanUserName, {
    `svc_participant`.ledger_api.users.create(
      id = scanUserName,
      actAs = Set.empty,
      readAs = Set(svcParty),
      primaryParty = Some(svcParty),
      participantAdmin = false,
    )
  })

  tools.ensureParticipantUser(`svc_participant`, directoryUserName, {
    `svc_participant`.ledger_api.users.create(
      id = directoryUserName,
      actAs = Set(svcParty),
      readAs = Set.empty,
      primaryParty = Some(svcParty),
      participantAdmin = true,
    )
  })

  // Allocate a party/user for each SV node
  Seq("SV1", "SV2", "SV3", "SV4").foreach(svName => {
    val svUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv(svName)

    tools.ensureParticipantUser(`svc_participant`, svUserName, {
      val svParty = `svc_participant`.parties.enable(svName.toLowerCase())

      `svc_participant`.ledger_api.users.create(
        id = svUserName,
        actAs = if (svName == "SV1") Set(svParty.toLf, svcParty) else Set(svParty.toLf),
        readAs = Set(svcParty),
        primaryParty = Some(svParty.toLf),
        participantAdmin = true,
      )
    })
  })
}
