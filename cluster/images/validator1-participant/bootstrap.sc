import $file.tools

def main() {
  tools.connectGlobalDomain(`validator1_participant`)

  val validatorServiceUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv("VALIDATOR")

  tools.ensureParticipantUser(`validator1_participant`, validatorServiceUserName, {
    val validatorParty = `validator1_participant`.parties.enable("validator1_validator_service_user")

    `validator1_participant`.ledger_api.users.create(
      id = validatorServiceUserName,
      actAs = Set(validatorParty.toLf),
      readAs = Set.empty,
      primaryParty = Some(validatorParty.toLf),
      participantAdmin = true,
    )
  })
}