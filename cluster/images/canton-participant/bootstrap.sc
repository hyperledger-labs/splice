import $file.tools

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

def main() {
  val userEnvVars = decode[Seq[String]](sys.env("CANTON_PARTICIPANT_USERS")).getOrElse(
    sys.error("Failed to parse users config")
  )
  userEnvVars.foreach { userEnvVar =>
    val user = sys.env.getOrElse(userEnvVar, sys.error(s"Failed to parse users config: environment variable $userEnvVar not found"))
    tools.createParticipantAdminUser(participant, user)
  }

  val domains =
    decode[Seq[tools.DomainDef]](sys.env.getOrElse("CANTON_PARTICIPANT_EXTRA_DOMAINS", "[]"))
      .getOrElse(
        sys.error("Failed to parse domains config")
      )
  domains.foreach { domain =>
    tools.connectDomain(participant, participant.health, domain)
  }
}
