import com.digitalasset.canton.topology.PartyId
val domainUrl = sys.env.get("DOMAIN_URL") match {
  case None =>
    sys.error(
      "Environment variable DOMAIN_URL was not set, set it to http://${targetcluster}.network.canton.global:5008"
    )
  case Some(url) => url
}

// Note: the validator user name is defined in validator.conf or validator-secure.conf
val validatorUserName = System.getProperty("VALIDATOR_USER_NAME", "validator_user")

logger.info("Starting participant node")
validatorParticipant.start()

logger.info(s"Connecting self-hosted validator to the domain $domainUrl")
validatorParticipant.domains.connect("global", domainUrl)

logger.info(s"Creating validator user: " + validatorUserName)
val validatorParty = validatorParticipant.ledger_api.parties
  .allocate("validator_service_user", "validator_service_user")
  .party

validatorParticipant.ledger_api.users.create(
  id = validatorUserName,
  actAs = Set(validatorParty),
  readAs = Set.empty,
  primaryParty = Some(validatorParty),
  participantAdmin = true,
)
logger.info("Validator participant bootstrap finished")
