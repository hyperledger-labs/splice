import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

def main() {
  val dars = decode[Seq[String]](sys.env.get("SPLICE_APP_DARS").getOrElse("[]")).getOrElse(
    sys.error("Failed to parse dars list")
  )
  val timeoutMinutes =
    decode[Int](sys.env.get("SPLICE_APP_INITIALIZATION_TIMEOUT_MINUTES").getOrElse("5")).getOrElse(
      sys.error("Failed to parse initialization timeout")
    )

  if (dars.isEmpty) {
    logger.info("No DARs specified to upload")
  } else {
    logger.info("Waiting for validator to finish init...")
    logger.debug(s"Loaded environment: ${sys.env}")
    validator_backend.waitForInitialization(timeoutMinutes.minutes)

    logger.info(s"Uploading DARs: ${dars}")
    dars.foreach(validator_backend.participantClient.upload_dar_unless_exists(_))
  }
}
