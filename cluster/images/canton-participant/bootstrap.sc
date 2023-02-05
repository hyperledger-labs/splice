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
}
