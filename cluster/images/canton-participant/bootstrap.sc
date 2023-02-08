import $file.tools

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

final case class EnvSubst(
  env: String
)

sealed trait PrimaryParty
final case class AllocateParty(allocate: String) extends PrimaryParty
final case class PartyFromUser(fromUser: EnvSubst) extends PrimaryParty

def main() {
  tools.connectGlobalDomain(participant)

  val users = decode[Seq[tools.UserDef]](sys.env("CANTON_PARTICIPANT_USERS")).getOrElse(
    sys.error("Failed to parse users config")
  )
  users.foreach { user =>
    tools.createUser(participant, user)
  }

  val domains = decode[Seq[tools.DomainDef]](sys.env.get("CANTON_PARTICIPANT_EXTRA_DOMAINS").getOrElse("[]")).getOrElse(
    sys.error("Failed to parse domains config")
  )
  domains.foreach { domain =>
    tools.connectDomain(participant, domain)
  }
}
