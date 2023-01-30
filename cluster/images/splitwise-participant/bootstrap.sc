import $file.tools

def main() {
  tools.connectGlobalDomain(`splitwise_participant`)

  val validatorServiceUserName = tools.getCnAppLedgerApiAuthUserNameFromEnv("SPLITWISE_VALIDATOR")

  // Ensure there's an service Party
  tools.ensureParticipantUser(`splitwise_participant`, validatorServiceUserName, {
    val validatorParty = `splitwise_participant`.parties.enable("splitwise_validator_service_user")

    `splitwise_participant`.ledger_api.users.create(
      id = validatorServiceUserName,
      actAs = Set(validatorParty.toLf),
      readAs = Set.empty,
      primaryParty = Some(validatorParty.toLf),
      participantAdmin = true,
    )
  });
}