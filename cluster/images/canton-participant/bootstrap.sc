import $file.tools

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

def main() {
  val domains =
    decode[Seq[tools.DomainDef]](sys.env.getOrElse("CANTON_PARTICIPANT_EXTRA_DOMAINS", "[]"))
      .getOrElse(
        sys.error("Failed to parse domains config")
      )
  domains.foreach { domain =>
    tools.connectDomain(participant, participant.health, domain)
  }
}
