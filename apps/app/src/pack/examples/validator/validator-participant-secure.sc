// Note: the validator user name is defined in validator.conf or validator-secure.conf
val validatorUserName = sys.env.get("NETWORK_AUTH_VALIDATOR_USER_NAME") match {
  case None =>
    sys.error(
      "Environment variable NETWORK_AUTH_VALIDATOR_USER_NAME was not set, set it based on the documentation"
    )
  case Some(name) => name
}

println("Starting participant node")
validatorParticipant.start()

println("Secure validator participant bootstrap finished")

validatorParticipant.ledger_api.users.create(
  id = validatorUserName,
  actAs = Set.empty,
  readAs = Set.empty,
  primaryParty = None,
  participantAdmin = true,
)
println("Validator participant bootstrap finished")
