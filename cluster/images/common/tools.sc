import scala.util.Try
import com.digitalasset.canton.console.ParticipantReference
import com.digitalasset.canton.admin.api.client.data.User

def getCnAppLedgerApiAuthUserNameFromEnv(app: String) = {
  val envVar = s"CN_APP_${app}_LEDGER_API_AUTH_USER_NAME"
  sys.env.get(envVar)
    .getOrElse(sys.error(s"Environment variable ${envVar} does not exist"))
}

def connectGlobalDomain(p: ParticipantReference) {
    val domainLabel = "global"
    val domainUrl = "http://canton-domain:5008"

    logger.info("Ensuring connection to global domain.")
    p.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(p.domains.active(domainLabel))

    logger.info("Executing self ping to verify connection to global domain...")
    p.health.ping(p)
}

def ensureParticipantUser(p: ParticipantReference, userName: String, createUser: => User): User = {
  val user = Try(p.ledger_api.users.get(userName)).toOption.getOrElse({
    logger.info(s"User missing, creating now: ${userName}")
    createUser
  })

  logger.info(s"User ${userName} is ${user}")

  user
}
