val validatorUserName = sys.env.get("VALIDATOR_USER_NAME").getOrElse("validator_user")

println("Starting participant node")
splitwellParticipant.start()

println(s"Creating validator user:" + validatorUserName)
splitwellParticipant.ledger_api.users.create(
  id = validatorUserName,
  actAs = Set.empty,
  readAs = Set.empty,
  primaryParty = None,
  participantAdmin = true,
)
logger.info("Splitwell participant bootstrap finished")
