import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

def main() {
  val dars = decode[Seq[String]](sys.env.get("CN_APP_DARS").getOrElse("[]")).getOrElse(
    sys.error("Failed to parse dars list")
  )

  logger.info("Waiting for validator to finish init...")
  validator_backend.waitForInitialization(2.minutes)

  // TODO (M3-90): This will later be replaced by the app manager
  logger.info(s"Uploading DARs: ${dars}")
  dars.foreach(validator_backend.participantClient.upload_dar_unless_exists(_))
}
