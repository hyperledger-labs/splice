// Note: the validator user name is defined in validator.conf or validator-secure.conf
val validatorUserName = sys.env.get("VALIDATOR_USER_NAME").getOrElse("validator_user")

logger.info("Starting participant node")
validatorParticipant.start()

logger.info(s"Creating validator user: " + validatorUserName)
validatorParticipant.ledger_api.users.create(
  id = validatorUserName,
  actAs = Set.empty,
  readAs = Set.empty,
  primaryParty = None,
  participantAdmin = true,
)
logger.info("Validator participant bootstrap finished")
